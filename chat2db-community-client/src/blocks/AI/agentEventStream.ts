import type { AgentEvent } from '@/service/agent';
import { isTerminalAgentEvent, mergeAgentEvents } from './agentEvents';

type EventQuery = { sessionId: string; afterSequence?: number; beforeSequence?: number; limit: number };
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

const MAX_ALIGN_PAGES = 1;

/**
 * The newest events of a session. Event files are named by sequence, so the server can read this page
 * straight from the tail instead of scanning the whole history. A long conversation therefore opens
 * on its last turns instead of replaying every streamed delta.
 */
export async function readAgentHistoryTail(
  read: ReadAgentEvents, sessionId: string, signal: AbortSignal, lastEventSequence: number) {
  if (lastEventSequence < 1) return [];
  const page = await readEventPage(read,
    { sessionId, beforeSequence: lastEventSequence + 1, limit: PAGE_SIZE }, signal);
  return alignPageToRunStart(read, sessionId, signal, page);
}

/** The page of events just before {@code beforeSequence}, aligned to a turn boundary. */
export async function readAgentHistoryBefore(
  read: ReadAgentEvents, sessionId: string, signal: AbortSignal, beforeSequence: number) {
  if (beforeSequence < 2) return [];
  const page = await readEventPage(read, { sessionId, beforeSequence, limit: PAGE_SIZE }, signal);
  return alignPageToRunStart(read, sessionId, signal, page);
}

/** Extends a cut-off page backwards until it starts where a turn starts, or the history does. */
async function alignPageToRunStart(
  read: ReadAgentEvents, sessionId: string, signal: AbortSignal, page: AgentEvent[]) {
  let loaded = page.filter((event) => event.sessionId === sessionId);
  for (let attempt = 0; attempt < MAX_ALIGN_PAGES; attempt += 1) {
    if (signal.aborted || loaded.length < PAGE_SIZE || loaded[0].sequence <= 1
      || loaded[0].type === 'RUN_ACCEPTED') break;
    const older = (await readEventPage(read, {
      sessionId, beforeSequence: loaded[0].sequence, limit: PAGE_SIZE }, signal))
      .filter((event) => event.sessionId === sessionId && event.sequence < loaded[0].sequence);
    signal.throwIfAborted();
    if (older.length === 0) break;
    loaded = mergeAgentEvents(older, loaded);
  }
  const boundary = loaded.findIndex((event) => event.type === 'RUN_ACCEPTED');
  traceAgentStage('history.page', {
    sessionId, beforeSequence: page[0]?.sequence ?? 0, received: page.length, total: loaded.length,
  });
  // A full page that starts inside a turn was cut off by the window, so drop that half turn. A short
  // page reached sequence 1: it is the real start of the history and keeps the turn it has.
  return boundary > 0 && loaded.length >= PAGE_SIZE ? loaded.slice(boundary) : loaded;
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
