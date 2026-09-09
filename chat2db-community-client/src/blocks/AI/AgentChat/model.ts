import type { AgentEvent } from '@/service/agent';

export interface AgentTranscriptMessage {
  id: string;
  runId: string;
  role: 'user' | 'assistant';
  content: string;
  status?: 'failed' | 'unknown' | 'cancelled';
}

const eventText = (payload: Record<string, unknown>) => {
  for (const key of ['content', 'text', 'delta']) {
    const value = payload[key];
    if (typeof value === 'string') return value;
    if (value && typeof value === 'object') {
      const nested = value as Record<string, unknown>;
      if (typeof nested.text === 'string') return nested.text;
      if (typeof nested.content === 'string') return nested.content;
    }
  }
  const assistantEvent = payload.assistantMessageEvent;
  if (assistantEvent && typeof assistantEvent === 'object') {
    const delta = (assistantEvent as Record<string, unknown>).delta;
    if (typeof delta === 'string') return delta;
  }
  return '';
};

export const mergeAgentEvents = (current: AgentEvent[], incoming: AgentEvent[]) => {
  const events = new Map<number, AgentEvent>();
  [...current, ...incoming].forEach((event) => events.set(event.sequence, event));
  return [...events.values()].sort((left, right) => left.sequence - right.sequence);
};

export const buildAgentTranscript = (events: AgentEvent[]): AgentTranscriptMessage[] => {
  const messages: AgentTranscriptMessage[] = [];
  const assistants = new Map<string, AgentTranscriptMessage>();
  mergeAgentEvents([], events).forEach((event) => {
    const runId = event.runId || event.id;
    if (event.type === 'RUN_ACCEPTED') {
      const text = typeof event.payload.text === 'string' ? event.payload.text : '';
      if (text) messages.push({ id: `user-${event.id}`, runId, role: 'user', content: text });
      return;
    }
    if (event.type === 'ASSISTANT_MESSAGE_STARTED' || event.type === 'ASSISTANT_TEXT_DELTA') {
      let assistant = assistants.get(runId);
      if (!assistant) {
        assistant = { id: `assistant-${runId}`, runId, role: 'assistant', content: '' };
        assistants.set(runId, assistant);
        messages.push(assistant);
      }
      if (event.type === 'ASSISTANT_TEXT_DELTA') assistant.content += eventText(event.payload);
      return;
    }
    const assistant = assistants.get(runId);
    if (!assistant) return;
    if (event.type === 'RUN_FAILED') assistant.status = 'failed';
    if (event.type === 'RUN_OUTCOME_UNKNOWN') assistant.status = 'unknown';
    if (event.type === 'RUN_CANCELLED') assistant.status = 'cancelled';
  });
  return messages;
};

export const isTerminalAgentEvent = (event: AgentEvent) =>
  ['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'RUN_SUSPENDED', 'RUN_OUTCOME_UNKNOWN'].includes(event.type);
