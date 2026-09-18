import type { AgentEvent } from '@/service/agent';
import { isTerminalAgentEvent, mergeAgentEvents } from './agentEvents';

type EventQuery = { sessionId: string; afterSequence: number; limit: number };
export type ReadAgentEvents = (query: EventQuery, options: { signal: AbortSignal }) => Promise<AgentEvent[]>;
const PAGE_SIZE = 200;
const READ_TIMEOUT_MS = 15_000;
const MAX_RETRY_DELAY_MS = 10_000;
const MAX_RECONNECT_ATTEMPTS = 8;

/** A business error repeats identically, so only transport-shaped failures are worth retrying. */
const isPermanentReadError = (error: unknown) =>
  typeof error === 'object' && error !== null && Boolean((error as { errorCode?: unknown }).errorCode);

export const traceAgentStage = (stage: string, fields: Record<string, unknown>) => {
  console.debug('[AgentTrace] ' + JSON.stringify({ stage, ...fields }));
};

export const activeAgentRunId = (events: AgentEvent[]) => {
  const accepted = [...events].reverse().find((event) => event.type === 'RUN_ACCEPTED');
  return accepted?.runId && !events.some((event) =>
    event.runId === accepted.runId && isTerminalAgentEvent(event)) ? accepted.runId : undefined;
};

async function readEventPage(read: ReadAgentEvents, query: EventQuery, signal: AbortSignal, reconnect = false) {
  let retryDelay = 1_000;
  let attempt = 0;
  while (!signal.aborted) {
    const controller = new AbortController();
    const abort = () => controller.abort(signal.reason);
    signal.addEventListener('abort', abort, { once: true });
    const timer = setTimeout(() => controller.abort(new Error('Agent event request timed out')), READ_TIMEOUT_MS);
    let rejectAborted: () => void = () => {};
    try {
      const aborted = new Promise<never>((_, reject) => {
        rejectAborted = () => reject(controller.signal.reason);
        controller.signal.addEventListener('abort', rejectAborted, { once: true });
      });
      // The desktop bridge may settle late even after abort. Race the deadline
      // so observation can recover without treating transport loss as run failure.
      return await Promise.race([read({ ...query }, { signal: controller.signal }), aborted]);
    } catch (error) {
      if (signal.aborted) return [];
      // Bounded reconnects: a failure that never heals must reach the caller so the run can end visibly.
      if (!reconnect || ++attempt > MAX_RECONNECT_ATTEMPTS || isPermanentReadError(error)) throw error;
      traceAgentStage('events.reconnecting', { sessionId: query.sessionId, afterSequence: query.afterSequence, retryDelay, attempt });
    } finally {
      clearTimeout(timer);
      signal.removeEventListener('abort', abort);
      controller.signal.removeEventListener('abort', rejectAborted);
    }
    await waitForPoll(signal, retryDelay);
    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_DELAY_MS);
  }
  return [];
}

export interface AgentHistoryWindow {
  /** First sequence to load, so a long conversation can start at its newest events. */
  fromSequence?: number;
  /** Maximum number of events to load for this window. */
  maxEvents?: number;
}

export async function readAgentHistory(read: ReadAgentEvents, sessionId: string, signal: AbortSignal,
  window: AgentHistoryWindow = {}) {
  const maxEvents = window.maxEvents ?? Number.POSITIVE_INFINITY;
  let events: AgentEvent[] = [];
  let sequence = window.fromSequence ?? 0;
  while (!signal.aborted) {
    const limit = Math.max(1, Math.min(PAGE_SIZE, maxEvents - events.length));
    const page = await readEventPage(read, { sessionId, afterSequence: sequence, limit }, signal);
    signal.throwIfAborted();
    const incoming = page.filter((event) => event.sessionId === sessionId && event.sequence > sequence);
    events = mergeAgentEvents(events, incoming);
    traceAgentStage('history.page', { sessionId, afterSequence: sequence, received: page.length, total: events.length });
    if (incoming.length === 0 || page.length < limit || events.length >= maxEvents) return events;
    sequence = events[events.length - 1].sequence;
  }
  signal.throwIfAborted();
  return events;
}

export async function followAgentRun(
  read: ReadAgentEvents,
  sessionId: string,
  runId: string,
  afterSequence: number,
  signal: AbortSignal,
  onEvents: (events: AgentEvent[]) => void,
) {
  let sequence = afterSequence;
  while (!signal.aborted) {
    traceAgentStage('events.request', { sessionId, runId, afterSequence: sequence });
    const page = await readEventPage(read, { sessionId, afterSequence: sequence, limit: PAGE_SIZE }, signal, true);
    if (signal.aborted) {
      traceAgentStage('events.discarded', { sessionId, runId, received: page.length });
      return;
    }
    const incoming = mergeAgentEvents([], page).filter(
      (event) => event.sessionId === sessionId && event.sequence > sequence,
    );
    if (incoming.length) sequence = incoming[incoming.length - 1].sequence;
    const events = incoming.filter((event) => event.runId === runId);
    traceAgentStage('events.received', {
      sessionId, runId, sequence, events: events.map((event) => ({ sequence: event.sequence, type: event.type })),
    });
    if (events.length) onEvents(events);
    const terminal = events.find(isTerminalAgentEvent);
    if (terminal) return terminal;
    if (page.length < PAGE_SIZE) await waitForPoll(signal);
  }
}

function waitForPoll(signal: AbortSignal, delay = 400) {
  return new Promise<void>((resolve) => {
    const finish = () => {
      clearTimeout(timer);
      signal.removeEventListener('abort', finish);
      resolve();
    };
    const timer = setTimeout(finish, delay);
    signal.addEventListener('abort', finish, { once: true });
    if (signal.aborted) finish();
  });
}
