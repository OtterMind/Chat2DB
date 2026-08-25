import assert from 'node:assert/strict';
import {
  nextTaskWorkspaceTabKey,
  parseTaskWorkspaceRoute,
  shouldRefreshTaskDetail,
  taskWorkspaceRoutePath,
  taskWorkspaceRouteForConnectorManagement,
  taskWorkspaceTabsForConnectorManagement,
  taskWorkspaceTabKey,
  upsertTaskWorkspaceTab,
  type TaskWorkspaceTab,
} from './taskWorkspaceModel';

assert.deepEqual(parseTaskWorkspaceRoute('/tasks'), { type: 'BOARD' });
assert.deepEqual(parseTaskWorkspaceRoute('#/tasks/archive'), { type: 'ARCHIVE' });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/new'), { type: 'TASK_CREATE' });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/task%201'), { type: 'TASK_DETAIL', entityId: 'task 1' });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/schedules/new'), { type: 'SCHEDULES', entityId: undefined });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/schedules/schedule%201'), { type: 'SCHEDULES', entityId: 'schedule 1' });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/data-wikis'), { type: 'DATA_WIKI' });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/connectors'), { type: 'CONNECTORS' });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/agents'), { type: 'AGENT_MANAGER' });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/agents/new'), { type: 'AGENT_EDITOR' });
assert.deepEqual(parseTaskWorkspaceRoute('/tasks/agents/agent%201/edit'), { type: 'AGENT_EDITOR', entityId: 'agent 1' });

assert.equal(taskWorkspaceTabKey({ type: 'TASK_DETAIL', entityId: 'T-1' }), 'task:T-1');
assert.equal(taskWorkspaceRoutePath({ type: 'AGENT_EDITOR', entityId: 'A 1' }), '/tasks/agents/A%201/edit');
assert.equal(taskWorkspaceRoutePath({ type: 'DATA_WIKI' }), '/tasks/data-wikis');
assert.equal(taskWorkspaceTabKey({ type: 'CONNECTORS' }), 'connectors');
assert.equal(taskWorkspaceRoutePath({ type: 'CONNECTORS' }), '/tasks/connectors');
assert.deepEqual(
  taskWorkspaceRouteForConnectorManagement({ type: 'CONNECTORS' }, false),
  { type: 'BOARD' },
  'disabled DSH management must not restore a Connector Sessions route',
);
assert.deepEqual(
  taskWorkspaceRouteForConnectorManagement({ type: 'CONNECTORS' }, true),
  { type: 'CONNECTORS' },
);

const board: TaskWorkspaceTab = { key: 'board', type: 'BOARD', title: 'Tasks', closable: false };
const detail: TaskWorkspaceTab = { key: 'task:T-1', type: 'TASK_DETAIL', title: 'T-1', entityId: 'T-1', closable: true };
const connectors: TaskWorkspaceTab = { key: 'connectors', type: 'CONNECTORS', title: 'Connectors', closable: true };
assert.deepEqual(
  taskWorkspaceTabsForConnectorManagement([board, connectors], false),
  [board],
  'disabling DSH management removes an already-open Connector Sessions tab',
);
assert.deepEqual(taskWorkspaceTabsForConnectorManagement([board, connectors], true), [board, connectors]);
assert.deepEqual(upsertTaskWorkspaceTab([board], detail), [board, detail]);
assert.equal(upsertTaskWorkspaceTab([board, detail], { ...detail, title: 'Updated' }).length, 2);
assert.equal(nextTaskWorkspaceTabKey([board, detail], detail.key, detail.key), board.key);
assert.equal(shouldRefreshTaskDetail(detail, 'T-1', true), true);
assert.equal(shouldRefreshTaskDetail(board, 'T-1', true), false);
assert.equal(shouldRefreshTaskDetail(detail, 'T-1', false), false);
assert.equal(shouldRefreshTaskDetail(detail, 'T-1', false, true), true);
assert.equal(shouldRefreshTaskDetail(board, 'T-1', false, true), false);
