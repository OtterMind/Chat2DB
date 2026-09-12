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
  const current = [...entries].reverse().find((entry) => entry.kind === 'trace' && entry.trace.type === 'tool_call');
  if (current?.kind === 'trace') return { kind: 'tool', tool: {
    name: current.trace.name || '', ...(current.trace.description ? { description: current.trace.description } : {}),
  } };
  return { kind: 'starting' };
};

export const splitSkillMessage = (content: string) => {
  const match = content.match(/^(\s*)(\/skill:([a-z0-9]+(?:-[a-z0-9]+)*))(?=\s|$)/);
  return match
    ? { prefix: match[1], command: match[2], name: match[3], text: content.slice(match[0].length) }
    : undefined;
};
