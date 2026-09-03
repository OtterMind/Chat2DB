import assert from 'node:assert/strict';
import './mcpTokenRequestCoordinator.test';
import {
  canStartMcpOperation,
  initialMcpLifecycleState,
  reduceMcpLifecycleState,
} from './mcpLifecycle';
import type { McpStatus } from '@/typings/settings';

function status(operationId: string, configuredEnabled: boolean, appliedEnabled: boolean): McpStatus {
  return {
    operationId,
    configuredEnabled,
    appliedEnabled,
    runtimeState: appliedEnabled ? 'RUNNING' : 'STOPPED',
    restartRequired: configuredEnabled !== appliedEnabled,
  };
}

const loading = reduceMcpLifecycleState(initialMcpLifecycleState, {
  type: 'START',
  operation: 'loading',
  operationId: 'load-1',
});
const saving = reduceMcpLifecycleState(loading, {
  type: 'START',
  operation: 'saving',
  operationId: 'save-2',
});

assert.strictEqual(
  reduceMcpLifecycleState(saving, { type: 'STATUS', status: status('load-1', false, false) }),
  saving,
  'a delayed status response must not replace the newer save operation',
);

const saved = reduceMcpLifecycleState(saving, {
  type: 'STATUS',
  status: status('save-2', true, false),
});
assert.equal(saved.status?.configuredEnabled, true);
assert.equal(saved.status?.appliedEnabled, false);
assert.equal(saved.status?.restartRequired, true);
assert.equal(saved.pendingOperation, null);

const restarting = reduceMcpLifecycleState(saved, {
  type: 'START',
  operation: 'restarting',
  operationId: 'restart-3',
});
assert.strictEqual(
  reduceMcpLifecycleState(restarting, { type: 'FAILURE', operationId: 'save-2', error: 'stale failure' }),
  restarting,
  'an old failure must not replace the active restart state',
);

const failed = reduceMcpLifecycleState(restarting, {
  type: 'FAILURE',
  operationId: 'restart-3',
  error: 'restart helper failed',
});
assert.equal(failed.pendingOperation, null);
assert.equal(failed.error, 'restart helper failed');

assert.equal(canStartMcpOperation(null), true);
assert.equal(
  canStartMcpOperation('save-4'),
  false,
  'a second operation in the same render frame must not schedule another Java-side effect',
);

console.log('MCP lifecycle operation ordering tests passed');
