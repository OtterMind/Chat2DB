import assert from 'node:assert/strict';
import type { SqlExecutionLogRecord } from '@/service/sqlExecutionLog';
import {
  createExecutionConsoleOrderStorageKey,
  getLatestExecutionEdgeScrollTop,
  orderExecutionLogRecords,
  persistExecutionConsoleOrder,
  readExecutionConsoleOrder,
} from './executionConsolePreferences';

function record(id: string, executionId: string, statementSequence: number): SqlExecutionLogRecord {
  return {
    id,
    executionId,
    statementSequence,
    startedAtEpochMs: statementSequence,
    status: 'success',
    sql: `select ${statementSequence}`,
    context: {},
    outputs: [],
    pendingRowCounts: {},
  };
}

const storageKey = createExecutionConsoleOrderStorageKey('community', 'community');
const values = new Map<string, string>();
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => {
    values.set(key, value);
  },
};

assert.equal(storageKey, 'chat2db.community.community.execution-console.order.v1');
assert.notEqual(
  storageKey,
  createExecutionConsoleOrderStorageKey('community', 'desktop'),
  'runtime modes keep independent preferences',
);
assert.equal(readExecutionConsoleOrder(undefined, storageKey), 'oldest-first');
assert.equal(readExecutionConsoleOrder(storage, storageKey), 'oldest-first');

values.set(storageKey, 'invalid');
assert.equal(readExecutionConsoleOrder(storage, storageKey), 'oldest-first');
values.set(storageKey, 'newest-first');
assert.equal(readExecutionConsoleOrder(storage, storageKey), 'newest-first');
persistExecutionConsoleOrder(storage, storageKey, 'oldest-first');
assert.equal(values.get(storageKey), 'oldest-first');

const unavailableStorage = {
  getItem: () => {
    throw new Error('unavailable');
  },
  setItem: () => {
    throw new Error('unavailable');
  },
};
assert.equal(readExecutionConsoleOrder(unavailableStorage, storageKey), 'oldest-first');
assert.doesNotThrow(() => persistExecutionConsoleOrder(unavailableStorage, storageKey, 'newest-first'));

const records = [
  record('execution-1-statement-1', 'execution-1', 1),
  record('execution-1-statement-2', 'execution-1', 2),
  record('execution-2-statement-1', 'execution-2', 1),
  record('execution-3-statement-1', 'execution-3', 1),
  record('execution-3-statement-2', 'execution-3', 2),
];

assert.deepEqual(
  orderExecutionLogRecords(records, 'oldest-first').map((item) => item.id),
  records.map((item) => item.id),
);
assert.deepEqual(
  orderExecutionLogRecords(records, 'newest-first').map((item) => item.id),
  [
    'execution-3-statement-1',
    'execution-3-statement-2',
    'execution-2-statement-1',
    'execution-1-statement-1',
    'execution-1-statement-2',
  ],
  'newest-first reverses execution groups without reversing statements inside a group',
);
assert.deepEqual(
  records.map((item) => item.id),
  [
    'execution-1-statement-1',
    'execution-1-statement-2',
    'execution-2-statement-1',
    'execution-3-statement-1',
    'execution-3-statement-2',
  ],
  'ordering does not mutate reducer state',
);

assert.equal(getLatestExecutionEdgeScrollTop(500, 'newest-first'), 0);
assert.equal(getLatestExecutionEdgeScrollTop(500, 'oldest-first'), 500);

console.log('Execution console preference tests passed');
