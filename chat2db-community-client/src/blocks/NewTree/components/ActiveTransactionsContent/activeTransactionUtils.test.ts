import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import {
  ActiveTransactionLoadErrorKind,
  beginActiveTransactionRefresh,
  classifyActiveTransactionLoadError,
  formatActiveTransactionStartedAt,
  getActiveTransactionRowKey,
  getLiveTransactionAge,
  getTransactionConnectionInspection,
  invalidateActiveTransactionRefresh,
  isLatestActiveTransactionRefresh,
} from './activeTransactionUtils';
import {
  ACTIVE_TRANSACTION_PROCESS_PRIVILEGE_ERROR_CODE,
  ActiveTransactionLockMetadataSource,
  ActiveTransactionLockMetadataState,
  ActiveTransactionQueryState,
  ActiveTransactionSessionState,
  MYSQL_ACTIVE_TRANSACTION_STATE,
} from '@/constants/activeTransaction';
import type { IActiveTransactionItem } from '@/service/sql';

const waitingTransaction: IActiveTransactionItem = {
  trxId: '421337',
  state: MYSQL_ACTIVE_TRANSACTION_STATE.LOCK_WAIT,
  startedAt: '2026-08-31 12:00:00',
  ageSeconds: 12,
  isolationLevel: 'REPEATABLE READ',
  rowsLocked: 1,
  rowsModified: 0,
  lockStructs: 2,
  threadId: 45,
  user: 'ops002_user',
  host: '127.0.0.1:50000',
  db: 'ops002_test',
  query: null,
  queryState: ActiveTransactionQueryState.UNAVAILABLE,
  sessionAvailable: true,
  sessionState: ActiveTransactionSessionState.LIVE,
  canOpenSession: true,
  connectionInspectionSql: 'owner inspection sql',
  waitingLockId: '421337:7:3:2',
  blockingLockId: '421336:7:3:2',
  blockingTrxId: '421336',
  blockingThreadId: 44,
  blockingSessionAvailable: true,
  canOpenBlockingSession: true,
  blockingConnectionInspectionSql: 'blocker inspection sql',
  blockingUser: 'ops002_admin',
  blockingHost: '127.0.0.1:49999',
  blockingDb: 'ops002_test',
  lockMetadataState: ActiveTransactionLockMetadataState.AVAILABLE,
  lockMetadataSource: ActiveTransactionLockMetadataSource.MYSQL_80_PERFORMANCE_SCHEMA,
};

assert.equal(getActiveTransactionRowKey(waitingTransaction), '421337:421337:7:3:2:421336:7:3:2:421336:45');
assert.notEqual(
  getActiveTransactionRowKey(waitingTransaction),
  getActiveTransactionRowKey({
    ...waitingTransaction,
    blockingLockId: '421338:7:3:2',
    blockingTrxId: '421338',
  }),
);
assert.deepEqual(getTransactionConnectionInspection(waitingTransaction, 'owner'), {
  connectionId: 45,
  sql: 'owner inspection sql',
});
assert.deepEqual(getTransactionConnectionInspection(waitingTransaction, 'blocker'), {
  connectionId: 44,
  sql: 'blocker inspection sql',
});
assert.equal(
  getTransactionConnectionInspection({ ...waitingTransaction, connectionInspectionSql: null }, 'owner'),
  null,
);
assert.deepEqual(
  classifyActiveTransactionLoadError({ errorCode: ACTIVE_TRANSACTION_PROCESS_PRIVILEGE_ERROR_CODE }),
  { kind: ActiveTransactionLoadErrorKind.PROCESS_PRIVILEGE_REQUIRED },
);
assert.deepEqual(classifyActiveTransactionLoadError({ errorMessage: 'connection failed' }), {
  kind: ActiveTransactionLoadErrorKind.OTHER,
  message: 'connection failed',
});
assert.deepEqual(classifyActiveTransactionLoadError(new Error('connection failed')), {
  kind: ActiveTransactionLoadErrorKind.OTHER,
  message: 'connection failed',
});
const refreshGenerationRef = { current: 0 };
const olderRefresh = beginActiveTransactionRefresh(refreshGenerationRef);
const newerRefresh = beginActiveTransactionRefresh(refreshGenerationRef);
assert.equal(isLatestActiveTransactionRefresh(refreshGenerationRef, olderRefresh), false);
assert.equal(isLatestActiveTransactionRefresh(refreshGenerationRef, newerRefresh), true);
invalidateActiveTransactionRefresh(refreshGenerationRef);
assert.equal(isLatestActiveTransactionRefresh(refreshGenerationRef, newerRefresh), false);
assert.equal(getLiveTransactionAge(12, 1_000, 4_900), 15);
assert.equal(getLiveTransactionAge(12, 5_000, 4_000), 12);
assert.equal(getLiveTransactionAge(null, 1_000, 4_900), null);
assert.equal(formatActiveTransactionStartedAt('2026-08-31 12:00:00'), '2026-08-31 12:00:00');
assert.match(formatActiveTransactionStartedAt(1_788_352_626_000), /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
assert.notEqual(formatActiveTransactionStartedAt(1_788_352_626_000), '1788352626000');
assert.equal(formatActiveTransactionStartedAt(null), '-');
assert.equal(formatActiveTransactionStartedAt('not-a-date'), '-');
const activeTransactionsSource = readFileSync(
  'src/blocks/NewTree/components/ActiveTransactionsContent/index.tsx',
  'utf8',
);
const activeTransactionsStyle = readFileSync(
  'src/blocks/NewTree/components/ActiveTransactionsContent/index.less',
  'utf8',
);
assert.doesNotMatch(
  activeTransactionsSource,
  /fixed:\s*['"]right['"]/,
  'the session action column must not render the fixed-column divider in dark mode',
);
assert.doesNotMatch(
  activeTransactionsSource,
  /workspace\.ops\.sessionAction/,
  'session inspection links belong beside their target thread IDs instead of a duplicate action column',
);
assert.doesNotMatch(
  activeTransactionsSource,
  /queryState\s*===\s*['"]UNAVAILABLE['"]/,
  'query state comparisons must use the centralized enum',
);
assert.match(activeTransactionsStyle, /ant-table-ping-right/);
assert.match(activeTransactionsStyle, /box-shadow:\s*none/);
