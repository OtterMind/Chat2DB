import type { DatabaseTypeCode } from './common';

export enum CreateTabIntroType {
  EditorTable = 'editorTable',
  EditTableData = 'editTableData',
}

export const LOCAL_SQL_FILE_SAVED_EVENT = 'chat2db:local-sql-file-saved';
export const SAVED_CONSOLE_UPDATED_EVENT = 'chat2db:saved-console-updated';
export const LOCAL_SQL_SESSION_DRAG_TYPE = 'application/x-chat2db-local-sql-session';

export interface SavedConsoleUpdatedEventDetail {
  dataSourceId: number;
  databaseType: DatabaseTypeCode;
  databaseName?: string;
  schemaName?: string;
}

// Types of workbench tabs
export enum WorkspaceTabType {
  LocalSQLFile = 'LocalSQLFile',
  CONSOLE = 'console',
  FUNCTION = 'function',
  PROCEDURE = 'procedure',
  VIEW = 'view',
  TRIGGER = 'trigger',
  EditTable = 'editTable',
  ViewView = 'viewView',
  CreateTable = 'createTable',
  EditTableData = 'editTableData',
  ViewAllTable = 'viewAllTable',
  ViewAllView = 'viewAllView',
  ViewERModal = 'viewERModal',
  ChangeAiTableInfo = 'changeAiTableInfo',
  RedisAllData = 'redisAllData',
  AccountPrivileges = 'accountPrivileges',
  ContentDiff = 'contentDiff',
}

// Some configurations corresponding to the type of workbench Tab
export const workspaceTabConfig: {
  [key in WorkspaceTabType]: {
    icon: string;
    iconExistDark?: boolean;
  };
} = {
  [WorkspaceTabType.CONSOLE]: {
    icon: 'icon-run-sql',
  },
  [WorkspaceTabType.LocalSQLFile]: {
    icon: 'icon-sql-file-1',
  },
  [WorkspaceTabType.VIEW]: {
    icon: 'icon-table-view',
    iconExistDark: true,
  },
  [WorkspaceTabType.FUNCTION]: {
    icon: 'icon-function',
  },
  [WorkspaceTabType.PROCEDURE]: {
    icon: 'icon-procedure',
  },
  [WorkspaceTabType.TRIGGER]: {
    icon: 'icon-trigger',
  },
  [WorkspaceTabType.EditTable]: {
    icon: 'icon-table-edit',
  },
  [WorkspaceTabType.ViewView]: {
    icon: 'icon-table-view',
  },
  [WorkspaceTabType.CreateTable]: {
    icon: 'icon-table-add',
  },
  [WorkspaceTabType.EditTableData]: {
    icon: 'icon-table',
    iconExistDark: true,
  },
  [WorkspaceTabType.ViewAllTable]: {
    icon: 'icon-table-all',
  },
  [WorkspaceTabType.ViewAllView]: {
    icon: 'icon-table-all',
  },
  [WorkspaceTabType.ChangeAiTableInfo]: {
    icon: 'icon-ai-table',
  },
  [WorkspaceTabType.RedisAllData]: {
    icon: 'icon-table-view',
  },
  [WorkspaceTabType.AccountPrivileges]: {
    icon: 'icon-users',
  },
  [WorkspaceTabType.ContentDiff]: {
    icon: 'icon-switch-horizontal',
  },
  [WorkspaceTabType.ViewERModal]: {
    icon: 'icon-er-modal',
  },
};

export const initUserConfigTree = {
  showComment: true,
  followActiveWorkspaceTab: true,
  workspaceLeftPanel: 'database' as const,
  sortDatabaseObjects: false,
};
