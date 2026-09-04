import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { TransactionIsolationLevel, TransactionMode } from '@/constants/transaction';
import {
  getTransactionModeLabelKey,
  isTransactionIsolationLevel,
  isTransactionMode,
  TRANSACTION_ISOLATION_OPTIONS,
  shouldChangeTransactionMode,
  TRANSACTION_MODE_OPTIONS,
} from './transactionToolbar';

assert.deepEqual(
  TRANSACTION_MODE_OPTIONS.map(({ value }) => value),
  [TransactionMode.AUTO, TransactionMode.MANUAL],
);
assert.equal(getTransactionModeLabelKey(), 'workspace.transaction.auto');
assert.equal(getTransactionModeLabelKey(TransactionMode.AUTO), 'workspace.transaction.auto');
assert.equal(getTransactionModeLabelKey(TransactionMode.MANUAL), 'workspace.transaction.manual');
assert.equal(shouldChangeTransactionMode(TransactionMode.AUTO, TransactionMode.AUTO), false);
assert.equal(shouldChangeTransactionMode(TransactionMode.AUTO, TransactionMode.MANUAL), true);
assert.equal(isTransactionMode(TransactionMode.MANUAL), true);
assert.equal(isTransactionMode(TransactionIsolationLevel.DEFAULT), false);
assert.deepEqual(
  TRANSACTION_ISOLATION_OPTIONS.map(({ value }) => value),
  [
    TransactionIsolationLevel.DEFAULT,
    TransactionIsolationLevel.READ_UNCOMMITTED,
    TransactionIsolationLevel.READ_COMMITTED,
    TransactionIsolationLevel.REPEATABLE_READ,
    TransactionIsolationLevel.SERIALIZABLE,
  ],
);
assert.equal(isTransactionIsolationLevel(TransactionIsolationLevel.READ_COMMITTED), true);
assert.equal(isTransactionIsolationLevel(TransactionMode.AUTO), false);

const operationLineSource = readFileSync(
  'src/components/SQLEditor/components/OperationLine/index.tsx',
  'utf8',
);
const editorSource = readFileSync(
  'src/components/SQLEditor/editor/SQLEditorWithOperation/index.tsx',
  'utf8',
);
assert.doesNotMatch(operationLineSource, /\bSwitch\b/, 'transaction mode must not regress to a binary switch');
assert.match(operationLineSource, /<Dropdown\b/, 'transaction mode must use a compact menu');
assert.match(
  operationLineSource,
  /transactionState\?\.supportedIsolationLevels\?\.includes\(option\.value\)/,
  'transaction isolation options must be filtered by backend-reported support',
);
assert.match(operationLineSource, /<Undo2\b/, 'manual mode must expose a recognizable rollback icon');
assert.equal(
  operationLineSource.match(
    /disabled=\{transactionActionsDisabled \|\| !transactionState\?\.inTransaction\}/g,
  )?.length,
  2,
  'commit and rollback must stay disabled until the console transaction starts',
);
assert.equal(
  editorSource.match(
    /inTransaction: outcomeUnknown \? current\?\.inTransaction \?\? true : Boolean\(result\?\.inTransaction\)/g,
  )?.length,
  2,
  'commit and rollback must keep recovery controls available until an unknown outcome is reconciled',
);
assert.equal(
  editorSource.match(/void reconcileCurrentTransactionState\(consoleId\)/g)?.length,
  4,
  'unknown responses and transport failures must reconcile with the server',
);
assert.match(
  operationLineSource,
  /disabled: transactionState\?\.inTransaction/,
  'isolation levels must be disabled while a transaction is open',
);
assert.match(
  editorSource,
  /nextMode === TransactionMode\.MANUAL[\s\S]*?staticMessage\.warning\(i18n\('workspace\.transaction\.myIsamNotProtected'\)\)/,
  'entering manual mode must warn that non-transactional engines cannot be rolled back',
);

const operationLineStyleSource = readFileSync(
  'src/components/SQLEditor/components/OperationLine/style.ts',
  'utf8',
);
assert.match(operationLineStyleSource, /height: 18px;[\s\S]*min-height: 26px;[\s\S]*margin: 4px 0;/);
assert.match(
  operationLineStyleSource,
  /transactionCommitButton:[\s\S]*?&:not\(:disabled\)[\s\S]*?color: \$\{token\.colorSuccess\}/,
  'commit must be green only while enabled',
);
assert.match(
  operationLineStyleSource,
  /transactionRollbackButton:[\s\S]*?&:not\(:disabled\)[\s\S]*?color: \$\{token\.colorError\}/,
  'rollback must be red only while enabled',
);

console.log('Transaction toolbar tests passed');
