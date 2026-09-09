import type { AgentEvent } from '@/service/agent';
import { isTerminalAgentEvent, mergeAgentEvents } from './agentEvents';

type EventQuery = { sessionId: string; afterSequence: number; limit: number };
export type ReadAgentEvents = (query: EventQuery, options: { signal: AbortSignal }) => Promise<AgentEvent[]>;
const PAGE_SIZE = 200;

export const traceAgentStage = (stage: string, fields: Record<string, unknown>) => {
  console.debug('[AgentTrace] ' + JSON.stringify({ stage, ...fields }));
};

export async function readAgentHistory(read: ReadAgentEvents, sessionId: string, signal: AbortSignal) {
  let events: AgentEvent[] = [];
  let sequence = 0;
  while (!signal.aborted) {
    const page = await read({ sessionId, afterSequence: sequence, limit: PAGE_SIZE }, { signal });
    signal.throwIfAborted();
    const incoming = page.filter((event) => event.sessionId === sessionId && event.sequence > sequence);
    events = mergeAgentEvents(events, incoming);
    traceAgentStage('history.page', { sessionId, afterSequence: sequence, received: page.length, total: events.length });
    if (incoming.length === 0 || page.length < PAGE_SIZE) return events;
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
    const page = await read({ sessionId, afterSequence: sequence, limit: PAGE_SIZE }, { signal });
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

function waitForPoll(signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    const finish = () => {
      clearTimeout(timer);
      signal.removeEventListener('abort', finish);
      resolve();
    };
    const timer = setTimeout(finish, 400);
    signal.addEventListener('abort', finish, { once: true });
    if (signal.aborted) finish();
  });
}
