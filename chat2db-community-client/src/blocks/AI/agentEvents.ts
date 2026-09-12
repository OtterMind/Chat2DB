import type { AgentEvent } from '@/service/agent';
import { agentContextDatabaseType, agentContextSummary } from './agentContext';

export interface AgentApprovalItem {
  id: string;
  sessionId: string;
  runId: string;
  toolName: string;
  command: string;
  workingDirectory: string;
  databaseTarget?: { dataSourceId: string; dataSourceName: string; database?: string; schema?: string };
  status: 'pending' | 'approved' | 'denied' | 'closed';
}

export const updateAgentApprovals = (
  current: AgentApprovalItem[], events: AgentEvent[],
): AgentApprovalItem[] => {
  const approvals = new Map(current.map((item) => [item.id, item]));
  for (const event of events) {
    const {
      approvalId, command, workingDirectory, toolName, approved, dataSourceId, dataSourceName, database, schema,
    } = event.payload;
    if (event.type === 'APPROVAL_REQUESTED' && typeof approvalId === 'string'
        && typeof command === 'string' && event.runId && !approvals.has(approvalId)) {
      approvals.set(approvalId, {
        id: approvalId, sessionId: event.sessionId, runId: event.runId, command,
        workingDirectory: typeof workingDirectory === 'string' ? workingDirectory : '',
        toolName: toolName === 'db_query' ? 'SQL' : toolName === 'powershell' ? 'PowerShell' : 'Bash',
        ...(toolName === 'db_query' && typeof dataSourceId === 'string' ? { databaseTarget: {
          dataSourceId,
          dataSourceName: typeof dataSourceName === 'string' ? dataSourceName : dataSourceId,
          database: typeof database === 'string' ? database : undefined,
          schema: typeof schema === 'string' ? schema : undefined,
        } } : {}),
        status: 'pending',
      });
    }
    if (event.type === 'APPROVAL_DECIDED' && typeof approvalId === 'string') {
      const item = approvals.get(approvalId);
      if (item && item.sessionId === event.sessionId && item.runId === event.runId) {
        approvals.set(approvalId, { ...item, status: approved === true ? 'approved' : 'denied' });
      }
    }
    if (isTerminalAgentEvent(event)) {
      for (const [id, item] of approvals) {
        if (item.sessionId === event.sessionId && item.runId === event.runId && item.status === 'pending') {
          approvals.set(id, { ...item, status: 'closed' });
        }
      }
    }
  }
  return [...approvals.values()];
};

export interface AgentTranscriptMessage {
  contextSummary?: string;
  contextDatabaseType?: string;
  id: string;
  runId: string;
  role: 'user' | 'assistant';
  content: string;
  status?: 'failed' | 'unknown' | 'cancelled';
  traceEntries: AgentTraceEntry[];
  timeline?: AgentTimelineEntry[];
}

export interface AgentTraceEntry {
  type: 'reasoning' | 'tool_call' | 'tool_result' | 'error';
  content?: string;
  name?: string;
  arguments?: string;
  id?: string;
  chartId?: string;
  failed?: boolean;
  description?: string;
  durationMs?: number;
  occurredAtMs?: number;
}

export type AgentTimelineEntry = { sequence: number; endSequence?: number } & (
  | { kind: 'text'; text: string }
  | { kind: 'trace'; trace: AgentTraceEntry }
  | { kind: 'question' | 'approval' | 'chart'; id: string }
);

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

export const appendAgentTimeline = (current: AgentTimelineEntry[], events: AgentEvent[]) => {
  const timeline = [...current];
  mergeAgentEvents([], events).forEach((event) => {
    const last = timeline[timeline.length - 1];
    if (last && event.sequence <= (last.endSequence || last.sequence)) return;
    if (event.type === 'ASSISTANT_MESSAGE_STARTED') {
      if (last?.kind === 'text' && !last.text.endsWith('\n\n')) {
        timeline[timeline.length - 1] = { ...last, text: last.text + '\n\n', endSequence: event.sequence };
      }
      return;
    }
    if (event.type === 'ASSISTANT_TEXT_DELTA') {
      const text = agentEventText(event.payload);
      if (!text) return;
      if (last?.kind === 'text') {
        timeline[timeline.length - 1] = { ...last, text: last.text + text, endSequence: event.sequence };
      } else {
        timeline.push({ kind: 'text', sequence: event.sequence, text });
      }
      return;
    }
    if (event.type === 'QUESTION_REQUESTED' && typeof event.payload.questionId === 'string') {
      timeline.push({ kind: 'question', sequence: event.sequence, id: event.payload.questionId });
      return;
    }
    if (event.type === 'APPROVAL_REQUESTED' && typeof event.payload.approvalId === 'string') {
      timeline.push({ kind: 'approval', sequence: event.sequence, id: event.payload.approvalId });
      return;
    }
    if (event.type === 'CHART_CREATED' && event.payload.chart && typeof event.payload.chart === 'object'
        && typeof (event.payload.chart as Record<string, unknown>).id === 'string') {
      timeline.push({ kind: 'chart', sequence: event.sequence, id: (event.payload.chart as Record<string, unknown>).id as string });
      return;
    }
    const trace = agentEventTrace(event);
    if (trace?.type === 'tool_result' && trace.durationMs === undefined && trace.occurredAtMs !== undefined) {
      const start = timeline.find((entry) => entry.kind === 'trace' && entry.trace.type === 'tool_call'
        && entry.trace.id === trace.id);
      if (start?.kind === 'trace' && start.trace.occurredAtMs !== undefined) {
        trace.durationMs = Math.max(0, Math.round(trace.occurredAtMs - start.trace.occurredAtMs));
      }
    }
    if (trace?.type === 'reasoning' && last?.kind === 'trace' && last.trace.type === 'reasoning') {
      timeline[timeline.length - 1] = { ...last, endSequence: event.sequence,
        trace: { ...last.trace, content: (last.trace.content || '') + (trace.content || '') } };
    } else if (trace) timeline.push({ kind: 'trace', sequence: event.sequence, trace });
  });
  return timeline;
};

export const buildAgentTranscript = (events: AgentEvent[]): AgentTranscriptMessage[] => {
  const messages: AgentTranscriptMessage[] = [];
  const assistants = new Map<string, AgentTranscriptMessage>();
  mergeAgentEvents([], events).forEach((event) => {
    const runId = event.runId;
    if (!runId) return;
    if (event.type === 'RUN_ACCEPTED') {
      const text = typeof event.payload.text === 'string' ? event.payload.text : '';
      const contextSummary = agentContextSummary(event.payload.context);
      const contextDatabaseType = agentContextDatabaseType(event.payload.context);
      if (text) messages.push({ id: `user-${event.id}`, runId, role: 'user', content: text, traceEntries: [],
        ...(contextSummary ? { contextSummary } : {}),
        ...(contextDatabaseType ? { contextDatabaseType } : {}) });
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
      const timeline = appendAgentTimeline(assistant.timeline || [], [event]);
      if (timeline.length) assistant.timeline = timeline;
    }
    const assistant = assistants.get(runId);
    if (!assistant) return;
    if (event.type === 'RUN_FAILED') assistant.status = 'failed';
    if (event.type === 'RUN_OUTCOME_UNKNOWN') assistant.status = 'unknown';
    if (event.type === 'RUN_CANCELLED') assistant.status = 'cancelled';
    if (event.type === 'RUN_COMPLETED' || event.type === 'RUN_SUSPENDED') delete assistant.status;
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

const eventTime = (value: unknown): number | undefined => {
  // Jackson can encode LocalDateTime as an array on older servers.
  const time = Array.isArray(value) && value.length >= 6 && value.every((part) => typeof part === 'number')
    ? Date.UTC(value[0], value[1] - 1, value[2], value[3], value[4], value[5], (value[6] || 0) / 1e6)
    : typeof value === 'string' ? Date.parse(value) : NaN;
  return Number.isFinite(time) ? time : undefined;
};

export const agentEventTrace = (event: AgentEvent): AgentTraceEntry | undefined => {
  const payload = event.payload;
  if (event.type === 'ASSISTANT_REASONING_DELTA') {
    return { type: 'reasoning', content: agentEventText(payload) };
  }
  if (event.type === 'RUN_FAILED' || event.type === 'RUN_OUTCOME_UNKNOWN') {
    return { type: 'error', content: agentErrorText(payload) || event.type };
  }
  const occurredAtMs = eventTime(event.occurredAt);
  const name = typeof payload.toolName === 'string' ? payload.toolName : undefined;
  const id = typeof payload.toolCallId === 'string' ? payload.toolCallId : event.id;
  const description = typeof payload.description === 'string' ? payload.description
    : payload.args && typeof payload.args === 'object' && typeof (payload.args as Record<string, unknown>).description === 'string'
      ? (payload.args as Record<string, unknown>).description as string : undefined;
  if (event.type === 'TOOL_CALL_RUNNING') {
    return { type: 'tool_call', id, name, description, ...(occurredAtMs === undefined ? {} : { occurredAtMs }),
      arguments: JSON.stringify(payload.args || {}) };
  }
  if (event.type === 'TOOL_CALL_COMPLETED' || event.type === 'TOOL_CALL_FAILED') {
    const result = payload.result as { content?: { type: string; text?: string }[];
      details?: { data?: { chartId?: unknown }; durationMs?: unknown };
      durationMs?: unknown } | undefined;
    const content = Array.isArray(result?.content)
      ? result.content.filter((item) => item.type === 'text').map((item) => item.text || '')
.join('\n')
      : JSON.stringify(payload.result || payload);
    const chartId = result?.details?.data?.chartId;
    const durationCandidates = [payload.durationMs, result?.durationMs, result?.details?.durationMs];
    const durationMs = durationCandidates.find((value): value is number => typeof value === 'number'
      && Number.isFinite(value) && value >= 0);
    return { type: 'tool_result', id, name, content, ...(occurredAtMs === undefined ? {} : { occurredAtMs }),
      ...(durationMs === undefined ? {} : { durationMs }),
      ...(event.type === 'TOOL_CALL_FAILED' ? { failed: true } : {}),
      ...(name === 'render_chart' && typeof chartId === 'string' ? { chartId } : {}) };
  }
  return undefined;
};
