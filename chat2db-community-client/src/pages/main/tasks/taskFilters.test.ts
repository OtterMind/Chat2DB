import assert from 'node:assert/strict';

import type { AgentTask } from '@/service/agent';
import { filterTasks } from './taskFilters';

const tasks = [
  { id: '1', title: 'Channel analysis', assigneeAgentId: 'alpha', status: 'IN_PROGRESS' },
  { id: '2', title: 'Daily巡检', assigneeAgentId: 'beta', status: 'WAITING_APPROVAL' },
  { id: '3', title: 'Channel cleanup', assigneeAgentId: 'alpha', status: 'DONE' },
  { id: '4', title: 'Retry export', assigneeAgentId: 'gamma', status: 'BLOCKED' },
] as AgentTask[];

assert.deepEqual(filterTasks(tasks, { title: ' channel ' }).map((task) => task.id), ['1', '3']);
assert.deepEqual(filterTasks(tasks, { agentIds: ['alpha', 'beta'] }).map((task) => task.id), ['1', '2', '3']);
assert.deepEqual(filterTasks(tasks, { agentIds: ['beta'] }).map((task) => task.id), ['2']);
assert.deepEqual(filterTasks(tasks, { boardColumns: ['active'] }).map((task) => task.id), ['1', '4']);
assert.deepEqual(filterTasks(tasks, { boardColumns: ['approval', 'complete'] }).map((task) => task.id), ['2', '3']);
assert.deepEqual(filterTasks(tasks, { title: 'channel', agentIds: ['alpha'], boardColumns: ['complete'] }).map((task) => task.id), ['3']);
