import type { AgentTask } from '@/service/agent';
import { TASK_BOARD_COLUMNS, type TaskBoardColumnKey } from './taskModel';

export interface TaskFilters {
  title?: string;
  agentIds?: string[];
  boardColumns?: TaskBoardColumnKey[];
}

export function filterTasks(tasks: AgentTask[], filters: TaskFilters): AgentTask[] {
  const title = filters.title?.trim().toLocaleLowerCase();
  const agentIds = new Set(filters.agentIds || []);
  const boardColumns = new Set(filters.boardColumns || []);
  const statuses = new Set(
    TASK_BOARD_COLUMNS
      .filter((column) => boardColumns.has(column.key))
      .flatMap((column) => column.statuses),
  );
  return tasks.filter((task) => {
    if (title && !task.title.toLocaleLowerCase().includes(title)) return false;
    if (agentIds.size && !agentIds.has(task.assigneeAgentId)) return false;
    return !boardColumns.size || statuses.has(task.status);
  });
}
