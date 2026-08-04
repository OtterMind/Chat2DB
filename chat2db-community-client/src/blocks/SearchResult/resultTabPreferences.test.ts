import assert from 'node:assert/strict';
import type { IManageResultData } from '@/typings';
import {
  createResultTabKeepHistoryStorageKey,
  createResultTabOrderStorageKey,
  orderExecutionResultsByBatch,
  persistResultTabKeepHistory,
  persistResultTabOrder,
  readResultTabKeepHistory,
  readResultTabOrder,
  subscribeResultTabKeepHistory,
  subscribeResultTabOrder,
} from './resultTabPreferences';

function result(id: string, executionSequence?: number): IManageResultData {
  return {
    uuid: id,
    dataList: [],
    headerList: [],
    description: '',
    sql: `select '${id}'`,
    originalSql: `select '${id}'`,
    success: true,
    duration: 0,
    sqlType: 'SELECT' as IManageResultData['sqlType'],
    refreshTargets: [],
    extra: executionSequence === undefined ? {} : { executionSequence },
    pageNo: 1,
    pageSize: 20,
    fuzzyTotal: '0',
    hasNextPage: false,
  };
}

const keepHistoryStorageKey = createResultTabKeepHistoryStorageKey('community', 'community');
const orderStorageKey = createResultTabOrderStorageKey('community', 'community');
const values = new Map<string, string>();
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => {
    values.set(key, value);
  },
};

assert.equal(keepHistoryStorageKey, 'chat2db.community.community.result-tabs.keep-history.v1');
assert.equal(orderStorageKey, 'chat2db.community.community.result-tabs.order.v1');
assert.notEqual(
  keepHistoryStorageKey,
  createResultTabKeepHistoryStorageKey('community', 'desktop'),
  'runtime modes keep independent result history preferences',
);
assert.notEqual(
  orderStorageKey,
  createResultTabOrderStorageKey('enterprise', 'community'),
  'client editions keep independent result order preferences',
);

assert.equal(readResultTabKeepHistory(undefined, keepHistoryStorageKey), true);
assert.equal(readResultTabKeepHistory(storage, keepHistoryStorageKey), true);
values.set(keepHistoryStorageKey, 'invalid');
assert.equal(readResultTabKeepHistory(storage, keepHistoryStorageKey), true);
values.set(keepHistoryStorageKey, 'false');
assert.equal(readResultTabKeepHistory(storage, keepHistoryStorageKey), false);
values.set(keepHistoryStorageKey, 'true');
assert.equal(readResultTabKeepHistory(storage, keepHistoryStorageKey), true);
persistResultTabKeepHistory(storage, keepHistoryStorageKey, false);
assert.equal(values.get(keepHistoryStorageKey), 'false');

assert.equal(readResultTabOrder(undefined, orderStorageKey), 'oldest-first');
assert.equal(readResultTabOrder(storage, orderStorageKey), 'oldest-first');
values.set(orderStorageKey, 'invalid');
assert.equal(readResultTabOrder(storage, orderStorageKey), 'oldest-first');
values.set(orderStorageKey, 'newest-first');
assert.equal(readResultTabOrder(storage, orderStorageKey), 'newest-first');
persistResultTabOrder(storage, orderStorageKey, 'oldest-first');
assert.equal(values.get(orderStorageKey), 'oldest-first');

const keepHistoryUpdates: Array<[string, boolean]> = [];
const secondKeepHistoryUpdates: Array<[string, boolean]> = [];
const unsubscribeKeepHistory = subscribeResultTabKeepHistory((key, keepHistory) => {
  keepHistoryUpdates.push([key, keepHistory]);
});
const unsubscribeSecondKeepHistory = subscribeResultTabKeepHistory((key, keepHistory) => {
  secondKeepHistoryUpdates.push([key, keepHistory]);
});
persistResultTabKeepHistory(storage, keepHistoryStorageKey, true);
assert.deepEqual(keepHistoryUpdates, [[keepHistoryStorageKey, true]]);
assert.deepEqual(secondKeepHistoryUpdates, [[keepHistoryStorageKey, true]]);
unsubscribeKeepHistory();
persistResultTabKeepHistory(storage, keepHistoryStorageKey, false);
assert.deepEqual(keepHistoryUpdates, [[keepHistoryStorageKey, true]], 'unsubscribed editors stop receiving history');
assert.deepEqual(secondKeepHistoryUpdates, [
  [keepHistoryStorageKey, true],
  [keepHistoryStorageKey, false],
]);
unsubscribeSecondKeepHistory();

const orderUpdates: Array<[string, string]> = [];
const secondOrderUpdates: Array<[string, string]> = [];
const unsubscribeOrder = subscribeResultTabOrder((key, order) => {
  orderUpdates.push([key, order]);
});
const unsubscribeSecondOrder = subscribeResultTabOrder((key, order) => {
  secondOrderUpdates.push([key, order]);
});
persistResultTabOrder(storage, orderStorageKey, 'newest-first');
assert.deepEqual(orderUpdates, [[orderStorageKey, 'newest-first']]);
assert.deepEqual(secondOrderUpdates, [[orderStorageKey, 'newest-first']]);
unsubscribeOrder();
persistResultTabOrder(storage, orderStorageKey, 'oldest-first');
assert.deepEqual(orderUpdates, [[orderStorageKey, 'newest-first']], 'unsubscribed editors stop receiving order');
assert.deepEqual(secondOrderUpdates, [
  [orderStorageKey, 'newest-first'],
  [orderStorageKey, 'oldest-first'],
]);
unsubscribeSecondOrder();

const unavailableStorage = {
  getItem: () => {
    throw new Error('unavailable');
  },
  setItem: () => {
    throw new Error('unavailable');
  },
};
assert.equal(readResultTabKeepHistory(unavailableStorage, keepHistoryStorageKey), true);
assert.doesNotThrow(() => persistResultTabKeepHistory(unavailableStorage, keepHistoryStorageKey, false));
assert.equal(readResultTabOrder(unavailableStorage, orderStorageKey), 'oldest-first');
assert.doesNotThrow(() => persistResultTabOrder(unavailableStorage, orderStorageKey, 'newest-first'));

const results = [
  result('execution-1-statement-1', 1),
  result('execution-1-statement-2-result-1', 1),
  result('execution-1-statement-2-result-2', 1),
  result('execution-2-statement-1', 2),
  result('execution-3-statement-1', 3),
  result('execution-3-statement-2', 3),
];

assert.deepEqual(
  orderExecutionResultsByBatch(results, 'oldest-first').map((item) => item.uuid),
  results.map((item) => item.uuid),
);
assert.deepEqual(
  orderExecutionResultsByBatch(results, 'newest-first').map((item) => item.uuid),
  [
    'execution-3-statement-1',
    'execution-3-statement-2',
    'execution-2-statement-1',
    'execution-1-statement-1',
    'execution-1-statement-2-result-1',
    'execution-1-statement-2-result-2',
  ],
  'newest-first reverses execution batches without reversing results inside a batch',
);
assert.deepEqual(
  results.map((item) => item.uuid),
  [
    'execution-1-statement-1',
    'execution-1-statement-2-result-1',
    'execution-1-statement-2-result-2',
    'execution-2-statement-1',
    'execution-3-statement-1',
    'execution-3-statement-2',
  ],
  'ordering does not mutate canonical result state',
);

const resultsWithLegacyEntries = [
  result('legacy-first'),
  result('execution-1', 1),
  result('legacy-second'),
  result('execution-2', 2),
];
assert.deepEqual(
  orderExecutionResultsByBatch(resultsWithLegacyEntries, 'newest-first').map((item) => item.uuid),
  ['execution-2', 'execution-1', 'legacy-first', 'legacy-second'],
  'legacy results remain stable and are not discarded when their batch cannot be determined',
);

console.log('Result tab preference tests passed');
