import type { AgentApprovalItem, AgentTimelineEntry } from '../../agentEvents';
import type { AgentQuestionItem } from '../../agentQuestions';

export type AgentActivity =
  | { kind: 'thinking' | 'responding' | 'question' | 'approval' }
  | { kind: 'tool'; names: string[] };

export const getAgentActivity = (
  active: boolean, entries: AgentTimelineEntry[], runId: string | undefined,
  questions: AgentQuestionItem[], approvals: AgentApprovalItem[],
): AgentActivity | undefined => {
  if (!active) return undefined;
  if (questions.some((item) => item.runId === runId && item.status === 'pending')) return { kind: 'question' };
  if (approvals.some((item) => item.runId === runId && item.status === 'pending')) return { kind: 'approval' };
  const pending = new Map<string, string>();
  entries.forEach((entry) => {
    if (entry.kind !== 'trace' || !entry.trace.id) return;
    const { id, type, name } = entry.trace;
    if (type === 'tool_call') pending.set(id, name || '');
    if (type === 'tool_result') pending.delete(id);
  });
  if (pending.size) return { kind: 'tool', names: [...new Set(pending.values())].filter(Boolean) };
  return { kind: entries.at(-1)?.kind === 'text' ? 'responding' : 'thinking' };
};

export const splitSkillMessage = (content: string) => {
  const match = content.match(/^(\s*)(\/skill:([a-z0-9]+(?:-[a-z0-9]+)*))(?=\s|$)/);
  return match
    ? { prefix: match[1], command: match[2], name: match[3], text: content.slice(match[0].length) }
    : undefined;
};
