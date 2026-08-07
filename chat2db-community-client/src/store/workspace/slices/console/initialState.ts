import { IWorkspaceTab, IWorkspaceTabSplitLayout } from '@/typings/workspace';
import { IConsole } from '@/typings';

export type TransactionMode = 'auto' | 'manual';

export interface TransactionState {
  /** Whether the console is in manual mode (auto-commit off). */
  mode: TransactionMode;
  /** Whether an uncommitted transaction is currently open on the server. */
  inTransaction: boolean;
  /** Outcome of the last commit/rollback/release, when reported by the server. */
  lastOutcome?: string;
  /** Last error message associated with the transaction, if any. */
  lastError?: string;
}

export interface ConsoleState {
  consoleList: IConsole[] | null;
  savedConsoleList: IConsole[] | null;
  activeConsoleId: string | number | null;
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
  workspaceTabList: null,
  workspaceTabSplitLayout: null,
  recentlyClosedWorkspaceTabs: [],
  createConsoleLoading: false,
  editorList: {},
  transactionStateMap: {},
};
