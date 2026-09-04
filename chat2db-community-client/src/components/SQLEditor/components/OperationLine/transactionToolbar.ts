import { TransactionIsolationLevel, TransactionMode } from '@/constants/transaction';

export const TRANSACTION_MODE_OPTIONS = [
  {
    value: TransactionMode.AUTO,
    labelKey: 'workspace.transaction.auto',
  },
  {
    value: TransactionMode.MANUAL,
    labelKey: 'workspace.transaction.manual',
  },
] as const;

export const TRANSACTION_ISOLATION_OPTIONS = [
  {
    value: TransactionIsolationLevel.DEFAULT,
    labelKey: 'workspace.transaction.databaseDefault',
  },
  {
    value: TransactionIsolationLevel.READ_UNCOMMITTED,
    labelKey: 'workspace.transaction.readUncommitted',
  },
  {
    value: TransactionIsolationLevel.READ_COMMITTED,
    labelKey: 'workspace.transaction.readCommitted',
  },
  {
    value: TransactionIsolationLevel.REPEATABLE_READ,
    labelKey: 'workspace.transaction.repeatableRead',
  },
  {
    value: TransactionIsolationLevel.SERIALIZABLE,
    labelKey: 'workspace.transaction.serializable',
  },
] as const;

export const getTransactionModeLabelKey = (mode?: TransactionMode) =>
  mode === TransactionMode.MANUAL ? 'workspace.transaction.manual' : 'workspace.transaction.auto';

export const shouldChangeTransactionMode = (currentMode: TransactionMode, nextMode: TransactionMode) =>
  currentMode !== nextMode;

export const isTransactionMode = (value: string): value is TransactionMode =>
  TRANSACTION_MODE_OPTIONS.some((option) => option.value === value);

export const isTransactionIsolationLevel = (value: string): value is TransactionIsolationLevel =>
  TRANSACTION_ISOLATION_OPTIONS.some((option) => option.value === value);
