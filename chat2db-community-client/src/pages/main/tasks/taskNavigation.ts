import type { AgentTaskDetail } from '@/service/agent';

export const AGENT_TASK_CREATED_EVENT = 'agent:taskCreated';

const detailCache = new Map<string, AgentTaskDetail>();

export function cacheAgentTaskDetail(detail: AgentTaskDetail) {
  detailCache.set(detail.task.id, detail);
}

export function getCachedAgentTaskDetail(taskId: string) {
  return detailCache.get(taskId);
}

export function notifyAgentTaskCreated(detail: AgentTaskDetail) {
  cacheAgentTaskDetail(detail);
  window.dispatchEvent(new CustomEvent<AgentTaskDetail>(AGENT_TASK_CREATED_EVENT, { detail }));
}
