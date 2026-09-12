import type { AgentApprovalItem, AgentTimelineEntry } from '../../agentEvents';
import type { AgentQuestionItem } from '../../agentQuestions';

export type AgentActivity =
  | { kind: 'thinking' | 'responding' | 'question' | 'approval' }
  | { kind: 'tool'; tool: { name: string; description?: string } };

export const toolSummary = (entries: AgentTimelineEntry[]) => {
  const traces = entries.filter((entry): entry is Extract<AgentTimelineEntry, { kind: 'trace' }> => entry.kind === 'trace');
  const calls = traces.filter((entry) => entry.trace.type === 'tool_call');
  if (!calls.length) return undefined;
  const results = new Map(traces.filter((entry) => entry.trace.type === 'tool_result' && entry.trace.id)
    .map((entry) => [entry.trace.id as string, entry.trace]));
  const durations = calls.map((entry) => results.get(entry.trace.id as string)?.durationMs);
  return { count: calls.length, durationMs: durations.every((duration): duration is number => duration !== undefined)
    ? durations.reduce((total, duration) => total + duration, 0) : undefined };
};

export const getAgentActivity = (
  active: boolean, entries: AgentTimelineEntry[], runId: string | undefined,
  questions: AgentQuestionItem[], approvals: AgentApprovalItem[],
): AgentActivity | undefined => {
  if (!active) return undefined;
  if (questions.some((item) => item.runId === runId && item.status === 'pending')) return { kind: 'question' };
  if (approvals.some((item) => item.runId === runId && item.status === 'pending')) return { kind: 'approval' };
  const pending = new Map<string, { name: string; description?: string }>();
  entries.forEach((entry) => {
    if (entry.kind !== 'trace' || !entry.trace.id) return;
    const { id, type, name } = entry.trace;
    if (type === 'tool_call') pending.set(id, { name: name || '', description: entry.trace.description });
    if (type === 'tool_result') pending.delete(id);
  });
  const current = [...pending.values()].filter((tool) => tool.name).at(-1);
  if (current) return { kind: 'tool', tool: current.description ? current : { name: current.name } };
  return { kind: entries.at(-1)?.kind === 'text' ? 'responding' : 'thinking' };
};

export const splitSkillMessage = (content: string) => {
  const match = content.match(/^(\s*)(\/skill:([a-z0-9]+(?:-[a-z0-9]+)*))(?=\s|$)/);
  return match
    ? { prefix: match[1], command: match[2], name: match[3], text: content.slice(match[0].length) }
    : undefined;
};
