import assert from 'node:assert/strict';
import {
  canStartSqlxOperation,
  createSqlxOperationId,
  getSqlxErrorMessage,
  initialSqlxLifecycleState,
  isSqlxOperationRunning,
  reduceSqlxLifecycleState,
} from './sqlxLifecycle';
import type { SqlxOperation, SqlxStatus } from '@/typings/settings';

function status(operation?: SqlxOperation, version = '0.1.16'): SqlxStatus {
  return {
    state: 'installed',
    platform: 'mac',
    version,
    path: '/Users/dev/.local/bin/sqlx',
    source: 'chat2db',
    onPath: true,
    installDir: '/Users/dev/.local/bin',
    latest: { status: 'upToDate', version, checkedAt: 1 },
    operation,
  };
}

const running: SqlxOperation = {
  operationId: 'install-1',
  kind: 'install',
  step: 'downloading',
  percent: 42,
};

const installing = reduceSqlxLifecycleState(initialSqlxLifecycleState, {
  type: 'START',
  operation: 'installing',
  operationId: 'install-1',
});
assert.equal(installing.pending, 'installing');
assert.equal(installing.pendingOperationId, 'install-1');

const checking = reduceSqlxLifecycleState(installing, {
  type: 'START',
  operation: 'checking',
  operationId: 'check-2',
});
assert.strictEqual(
  reduceSqlxLifecycleState(checking, { type: 'STATUS', status: status(running) }),
  checking,
  'a status reported for an older operation must not replace the active one',
);

const progressed = reduceSqlxLifecycleState(installing, { type: 'STATUS', status: status(running) });
assert.equal(progressed.status?.operation?.percent, 42);
assert.equal(progressed.pending, 'installing', 'a running operation keeps the control disabled');
assert.equal(progressed.loaded, true);
assert.equal(isSqlxOperationRunning(progressed.status), true);

const finished = reduceSqlxLifecycleState(progressed, { type: 'STATUS', status: status() });
assert.equal(finished.pending, null, 'a status without an operation finishes the pending one');
assert.equal(finished.pendingOperationId, null);
assert.equal(finished.error, null);
assert.equal(isSqlxOperationRunning(finished.status), false);

const failedStep = reduceSqlxLifecycleState(installing, {
  type: 'STATUS',
  status: status({ operationId: 'install-1', kind: 'install', step: 'failed', message: 'checksum mismatch' }),
});
assert.equal(failedStep.pending, null, 'a failed step finishes the operation so the user can retry');
assert.equal(failedStep.status?.operation?.message, 'checksum mismatch');
assert.equal(isSqlxOperationRunning(failedStep.status), false);

assert.strictEqual(
  reduceSqlxLifecycleState(installing, { type: 'FAILURE', operationId: 'check-2', error: 'stale' }),
  installing,
  'a stale transport failure must not stop the running operation',
);

const failed = reduceSqlxLifecycleState(installing, {
  type: 'FAILURE',
  operationId: 'install-1',
  error: 'Java Query is not available',
});
assert.equal(failed.pending, null);
assert.equal(failed.error, 'Java Query is not available');

assert.equal(canStartSqlxOperation(null), true);
assert.equal(
  canStartSqlxOperation('install-3'),
  false,
  'a second operation in the same render frame must not schedule another Java-side effect',
);
assert.notEqual(createSqlxOperationId(), createSqlxOperationId());
assert.equal(getSqlxErrorMessage(new Error('boom')), 'boom');
assert.equal(getSqlxErrorMessage('boom'), 'boom');

console.log('SQLX lifecycle operation ordering tests passed');
