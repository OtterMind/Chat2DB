import { IWorkspaceTab, IWorkspaceTabSplitLayout } from '@/typings/workspace';
import { IConsole } from '@/typings';
import type { WorkspaceTabScrollRequest } from '../../utils/workspaceTabScrollRequest';
import {
  TransactionIsolationLevel,
  TransactionMode,
  type TransactionOutcome,
} from '@/constants/transaction';

export interface TransactionState {
  /** Whether the console is in manual mode (auto-commit off). */
  mode: TransactionMode;
  /** Whether an uncommitted transaction is currently open on the server. */
  inTransaction: boolean;
  /** Whether the server-side begin request is still pending. */
  opening?: boolean;
  /** Isolation level selected for the next or current manual transaction. */
  isolationLevel: TransactionIsolationLevel;
  /** Isolation levels reported by the current datasource's JDBC driver. */
  supportedIsolationLevels: TransactionIsolationLevel[];
  /** Outcome of the last commit/rollback/release, when reported by the server. */
  lastOutcome?: TransactionOutcome;
  /** Last error message associated with the transaction, if any. */
  lastError?: string;
}

export const createInitialTransactionState = (): TransactionState => ({
  mode: TransactionMode.AUTO,
  inTransaction: false,
  opening: false,
  isolationLevel: TransactionIsolationLevel.DEFAULT,
  supportedIsolationLevels: [],
});

export interface ConsoleState {
  consoleList: IConsole[] | null;
  savedConsoleList: IConsole[] | null;
  activeConsoleId: string | number | null;
  workspaceTabScrollRequest: WorkspaceTabScrollRequest | null;
  workspaceTabList: IWorkspaceTab[] | null;
  workspaceTabSplitLayout: IWorkspaceTabSplitLayout | null;
  recentlyClosedWorkspaceTabs: IWorkspaceTab[];
  createConsoleLoading: boolean;
  editorList: Record<number | string, any>;
  /**
   * Per-console manual-transaction state, keyed by consoleId. Runtime-only: intentionally
   * excluded from persistence so a reload never claims a transaction is open when the server
   * has none (the server registry does not survive restart either).
   */
  transactionStateMap: Record<number, TransactionState>;
}

export const initConsoleState = {
  consoleList: null,
  savedConsoleList: null,
  activeConsoleId: null,
  workspaceTabScrollRequest: null,
  workspaceTabList: null,
  workspaceTabSplitLayout: null,
  recentlyClosedWorkspaceTabs: [],
  createConsoleLoading: false,
  editorList: {},
  transactionStateMap: {},
};
