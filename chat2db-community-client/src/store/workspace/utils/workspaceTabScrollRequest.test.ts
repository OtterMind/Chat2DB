import assert from 'node:assert/strict';
import { createNextWorkspaceTabScrollRequest } from './workspaceTabScrollRequest';

const firstRequest = createNextWorkspaceTabScrollRequest(null, 'table-1');
assert.deepEqual(firstRequest, { tabId: 'table-1', requestId: 1 });

const repeatedRequest = createNextWorkspaceTabScrollRequest(firstRequest, 'table-1');
assert.deepEqual(
  repeatedRequest,
  { tabId: 'table-1', requestId: 2 },
  'reopening the active tab must create a new scroll request',
);

const nextTabRequest = createNextWorkspaceTabScrollRequest(repeatedRequest, 'table-2');
assert.deepEqual(nextTabRequest, { tabId: 'table-2', requestId: 3 });

console.log('Workspace tab scroll request tests passed');
