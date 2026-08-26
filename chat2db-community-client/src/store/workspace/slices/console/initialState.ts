import { IWorkspaceTab, IWorkspaceTabSplitLayout } from '@/typings/workspace';
import { IConsole } from '@/typings';
import type { WorkspaceTabScrollRequest } from '../../utils/workspaceTabScrollRequest';

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
};
