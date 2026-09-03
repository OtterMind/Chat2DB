import { CreateTabIntroType, WorkspaceTabType, DatabaseTypeCode, ConsoleStatus } from '@/constants';
import { ITreeNode } from '@/typings';
import type { TerminalOpenPosition } from '@/typings/settings';
import type { IConnectionEnv } from './connection';

export interface ICreateTabIntro {
  type: CreateTabIntroType;
  workspaceTabType: WorkspaceTabType;
  treeNodeData: ITreeNode;
}

export interface IWorkspaceTab {
  id: number | string; // Tab ID
  type: WorkspaceTabType; // Type of workspace tab
  title: string; // The name of the workspace tab
  uniqueData?: IBoundInfo;
  pinned?: boolean;
}

export type WorkspaceTabPaneId = string;
export type WorkspaceTabSplitDirection = 'horizontal' | 'vertical';

export type IWorkspaceTabPaneNode =
  | {
      type: 'pane';
      id: WorkspaceTabPaneId;
    }
  | {
      type: 'split';
      nodeId?: string;
      direction: WorkspaceTabSplitDirection;
      size?: number | string;
      first: IWorkspaceTabPaneNode;
      second: IWorkspaceTabPaneNode;
    };

export interface IWorkspaceTabSplitLayout {
  direction: WorkspaceTabSplitDirection;
  activePane: WorkspaceTabPaneId;
  lastNonTerminalActiveTabId?: number | string | null;
  paneTabIds: Record<WorkspaceTabPaneId, Array<number | string>>;
  activeTabIds: Partial<Record<WorkspaceTabPaneId, number | string | null>>;
  root?: IWorkspaceTabPaneNode;
}

export interface ColumnAlias {
  columnName: string; // List
  columnComment: string; // Column comments
  columnCommentAlias?: string; // Column comment alias
  columnExampleData?: string; // Column sample data
  columnEnumMap?: {
    [key: string]: string;
  }; // enum mapping
  foreignTableName?: string; // Foreign key table name
  foreignColumnName?: string; // Foreign key column name
  functionExamples?: string; // Function example
  deletedFlag?: string; // delete flag
}

export interface TableCommentExt {
  tableName: string;
  tableComment: string;
  tableNameAlias: string;
  tableCommentAlias: string;
  columnAlias: ColumnAlias[];
}

export interface IBoundInfo {
  consoleId?: number;
  workspaceTabId?: number | string;
  dataSourceId?: number;
  dataSourceName?: string;
  environmentId?: number | null;
  environment?: IConnectionEnv | null;
  identityColor?: string | null;
  watermarkEnabled?: boolean | null;
  watermarkContent?: string | null;
  databaseType?: DatabaseTypeCode;
  databaseName?: string;
  schemaName?: string;
  status?: ConsoleStatus;
  connectable?: boolean;
  supportDatabase?: boolean;
  supportSchema?: boolean;
  nameCustomized?: boolean | null;

  filePath?: string;
  fileExtension?: string;
  fileCharset?: string;
  fileBom?: boolean;
  filePreviewUrl?: string;
  filePreviewMimeType?: string;
  fileRootToken?: string;
  fileRelativePath?: string;
  terminalSessionId?: string;
  terminalCwd?: string;
  terminalShell?: string;
  terminalShellId?: string;
  terminalOpenPosition?: TerminalOpenPosition;
  viewName?: string;
  functionName?: string;
  procedureName?: string;
  triggerName?: string;
  eventName?: string;
  tableName?: string;
  user?: string;
  host?: string;
  popoverContent?: string;
  ddl?: string;
  loadSQL?: any;
  isNewObject?: boolean;
  readOnly?: boolean;
  diffOriginalText?: string;
  diffModifiedText?: string;
  diffLanguage?: string;
}

export interface ExecuteTableParams {
  tableName: string;
  applyId?: number;
}
