import type { AgentEvent } from '@/service/agent';

export interface AgentTranscriptMessage {
  id: string;
  runId: string;
  role: 'user' | 'assistant';
  content: string;
  status?: 'failed' | 'unknown' | 'cancelled';
  traceEntries: AgentTraceEntry[];
}

export interface AgentTraceEntry {
  type: 'reasoning' | 'tool_call' | 'tool_result' | 'error';
  content?: string;
  name?: string;
  arguments?: string;
  id?: string;
}

export const agentEventText = (payload: Record<string, unknown>) => {
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

export const appendAgentText = (current: string, events: AgentEvent[]) =>
  events.reduce((text, event) => {
    if (event.type === 'ASSISTANT_MESSAGE_STARTED' && text && !text.endsWith('\n\n')) return text + '\n\n';
    return event.type === 'ASSISTANT_TEXT_DELTA' ? text + agentEventText(event.payload) : text;
  }, current);

export const buildAgentTranscript = (events: AgentEvent[]): AgentTranscriptMessage[] => {
  const messages: AgentTranscriptMessage[] = [];
  const assistants = new Map<string, AgentTranscriptMessage>();
  mergeAgentEvents([], events).forEach((event) => {
    const runId = event.runId || event.id;
    if (event.type === 'RUN_ACCEPTED') {
      const text = typeof event.payload.text === 'string' ? event.payload.text : '';
      if (text) messages.push({ id: `user-${event.id}`, runId, role: 'user', content: text, traceEntries: [] });
      return;
    }
    {
      let assistant = assistants.get(runId);
      if (!assistant) {
        assistant = { id: `assistant-${runId}`, runId, role: 'assistant', content: '', traceEntries: [] };
        assistants.set(runId, assistant);
        messages.push(assistant);
      }
      assistant.content = appendAgentText(assistant.content, [event]);
      const trace = agentEventTrace(event);
      if (trace) assistant.traceEntries.push(trace);
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

export const agentErrorText = (error: unknown): string => {
  if (typeof error === 'string') return error;
  if (!error || typeof error !== 'object') return '';
  const value = error as Record<string, unknown>;
  for (const key of ['errorMessage', 'message', 'error', 'reason']) {
    const text = agentErrorText(value[key]);
    if (text) return text;
  }
  return '';
};

export const agentEventTrace = (event: AgentEvent): AgentTraceEntry | undefined => {
  const payload = event.payload;
  if (event.type === 'ASSISTANT_REASONING_DELTA') {
    return { type: 'reasoning', content: agentEventText(payload) };
  }
  if (event.type === 'RUN_FAILED' || event.type === 'RUN_OUTCOME_UNKNOWN') {
    return { type: 'error', content: agentErrorText(payload) || event.type };
  }
  const name = typeof payload.toolName === 'string' ? payload.toolName : undefined;
  const id = typeof payload.toolCallId === 'string' ? payload.toolCallId : event.id;
  if (event.type === 'TOOL_CALL_RUNNING') {
    return { type: 'tool_call', id, name, arguments: JSON.stringify(payload.args || {}) };
  }
  if (event.type === 'TOOL_CALL_COMPLETED' || event.type === 'TOOL_CALL_FAILED') {
    const result = payload.result as { content?: { type: string; text?: string }[] } | undefined;
    const content = Array.isArray(result?.content)
      ? result.content.filter((item) => item.type === 'text').map((item) => item.text || '')
.join('\n')
      : JSON.stringify(payload.result || payload);
    return { type: 'tool_result', id, name, content };
  }
  return undefined;
};
