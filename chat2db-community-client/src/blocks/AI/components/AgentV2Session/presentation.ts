import type { AgentApprovalItem, AgentTimelineEntry, AgentTraceEntry } from '../../agentEvents';
import type { AgentQuestionItem } from '../../agentQuestions';

export type AgentActivity =
  | { kind: 'starting' | 'cancelling' | 'question' | 'approval' }
  | { kind: 'tool'; tool: { name: string; description?: string } };

export interface ToolExecution {
  id: string;
  name: string;
  description?: string;
  arguments?: string;
  content?: string;
  durationMs?: number;
  outputs?: AgentTraceEntry['outputs'];
  completed: boolean;
  failed: boolean;
}

export const toolExecutions = (entries: AgentTraceEntry[]): ToolExecution[] => {
  const calls = new Map<string, ToolExecution>();
  for (const entry of entries) {
    if ((entry.type !== 'tool_call' && entry.type !== 'tool_result') || !entry.id) continue;
    const previous = calls.get(entry.id);
    const result = entry.type === 'tool_result';
    calls.set(entry.id, {
      id: entry.id, name: entry.name || previous?.name || '',
      description: entry.description || previous?.description,
      arguments: entry.arguments || previous?.arguments,
      content: result ? entry.content : previous?.content,
      durationMs: entry.durationMs ?? previous?.durationMs,
      ...(entry.outputs || previous?.outputs ? { outputs: entry.outputs || previous?.outputs } : {}),
      completed: result || !!previous?.completed,
      failed: result ? !!entry.failed : !!previous?.failed,
    });
  }
  return [...calls.values()];
};

export const toolSummary = (entries: AgentTraceEntry[]) => {
  const calls = toolExecutions(entries);
  if (!calls.length) return undefined;
  const durations = calls.map((entry) => entry.durationMs);
  return { count: calls.length, durationMs: durations.every((duration): duration is number => duration !== undefined)
    ? durations.reduce((total, duration) => total + duration, 0) : undefined };
};

export const getAgentActivity = (
  active: boolean, entries: AgentTimelineEntry[], runId: string | undefined,
  questions: AgentQuestionItem[], approvals: AgentApprovalItem[], cancelling = false,
): AgentActivity | undefined => {
  if (!active) return undefined;
  if (cancelling) return { kind: 'cancelling' };
  if (questions.some((item) => item.runId === runId && item.status === 'pending')) return { kind: 'question' };
  if (approvals.some((item) => item.runId === runId && item.status === 'pending')) return { kind: 'approval' };
  // A completed tool must not remain shown as the current activity while the
  // runtime is waiting for the next model event. Track unresolved calls by id
  // so the indicator reflects the tool that is actually running.
  const completed = new Set<string>();
  for (const entry of entries) {
    if (entry.kind === 'trace' && entry.trace.type === 'tool_result' && entry.trace.id) {
      completed.add(entry.trace.id);
    }
  }
  const current = [...entries].reverse().find((entry) => entry.kind === 'trace'
    && entry.trace.type === 'tool_call' && (!entry.trace.id || !completed.has(entry.trace.id)));
  if (current?.kind === 'trace') return { kind: 'tool', tool: {
    name: current.trace.name || '', ...(current.trace.description ? { description: current.trace.description } : {}),
  } };
  // Once a tool has completed, the runtime may spend a short period waiting
  // for the next model token. Keep that state explicit so the UI can show a
  // delayed thinking indicator without replacing the tool summary.
  const last = entries.at(-1);
  if (last?.kind === 'trace' && last.trace.type === 'tool_result'
      && last.trace.id && entries.some((entry) => entry.kind === 'trace'
        && entry.trace.type === 'tool_call' && entry.trace.id === last.trace.id)) {
    return { kind: 'starting' };
  }
  const receivedContent = entries.some((entry) => entry.kind === 'text' ? !!entry.text
    : entry.kind === 'trace' && entry.trace.type === 'reasoning' ? !!entry.trace.content : true);
  return receivedContent ? undefined : { kind: 'starting' };
};

export const splitSkillMessage = (content: string) => {
  const match = content.match(/^(\s*)(\/skill:([a-z0-9]+(?:-[a-z0-9]+)*))(?=\s|$)/);
  return match
    ? { prefix: match[1], command: match[2], name: match[3], text: content.slice(match[0].length) }
    : undefined;
};
