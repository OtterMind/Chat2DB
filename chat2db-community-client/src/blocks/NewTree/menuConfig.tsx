import { DatabaseTypeCode, OperationColumn, TreeNodeType } from '@/constants';

export const dropMenuConfig = {
  [DatabaseTypeCode.MONGODB]: {
    [TreeNodeType.DATA_SOURCE]: [
      OperationColumn.CloseConnection,
      OperationColumn.Divider,
      OperationColumn.CreateConsole,
      OperationColumn.Divider,
      OperationColumn.EditSource,
      OperationColumn.CopyDataSource,
      OperationColumn.CopyName,
      OperationColumn.CopyMcpConfig,
      OperationColumn.Divider,
      OperationColumn.Refresh,
      OperationColumn.MoveToGroup,
      OperationColumn.Divider,
      OperationColumn.RemoveDataSource,
    ],
    [TreeNodeType.TABLES]: [OperationColumn.CreateConsole, OperationColumn.ViewAllTable, OperationColumn.Refresh],
    [TreeNodeType.TABLE]: [
      OperationColumn.OpenTable,
      OperationColumn.CreateConsole,
      OperationColumn.Pin,
      OperationColumn.Divider,
      OperationColumn.CopyName,
    ],
  },
  [DatabaseTypeCode.REDIS]: {
    [TreeNodeType.DATA_SOURCE]: [
      OperationColumn.CreateConsole,
      OperationColumn.Divider,
      OperationColumn.EditSource,
      OperationColumn.CopyDataSource,
      OperationColumn.CopyName,
      OperationColumn.CopyMcpConfig,
      OperationColumn.Divider,
      OperationColumn.MoveToGroup,
      OperationColumn.Refresh,
      OperationColumn.Divider,
      OperationColumn.RemoveDataSource,
    ],
    [TreeNodeType.TABLES]: [OperationColumn.CreateConsole, OperationColumn.ViewAllTable, OperationColumn.Refresh],
    [TreeNodeType.TABLE]: [OperationColumn.OpenTable, OperationColumn.CreateConsole, OperationColumn.Pin],
  },
  [DatabaseTypeCode.CLICKHOUSE]: {
    [TreeNodeType.TABLES]: [
      OperationColumn.CreateConsole,
      OperationColumn.ViewAllTable,
      OperationColumn.CopyName,
      OperationColumn.Refresh,
    ],
    [TreeNodeType.TABLE]: [
      OperationColumn.OpenTable,
      OperationColumn.CreateConsole,
      OperationColumn.Pin,
      OperationColumn.Divider,
      OperationColumn.CopyName,
      OperationColumn.Divider,
      OperationColumn.GenerateTestData,
      OperationColumn.Divider,
      OperationColumn.DeleteTable,
    ],
  },
  [DatabaseTypeCode.ORACLE]: {
    [TreeNodeType.DATA_SOURCE]: [
      // connection
      OperationColumn.CloseConnection,
      OperationColumn.ApplyPermission,
      OperationColumn.Divider,
      // Operation
      OperationColumn.CreateConsole,
      OperationColumn.RunSqlFile,
      OperationColumn.Divider,
      // Edit & Copy
      OperationColumn.EditSource,
      OperationColumn.CopyDataSource,
      OperationColumn.CopyName,
      OperationColumn.CopyMcpConfig,
      OperationColumn.Divider,
      // Management
      OperationColumn.MoveToGroup,
      OperationColumn.ActiveTransactions,
      OperationColumn.Refresh,
      OperationColumn.Divider,
      OperationColumn.RemoveDataSource,
    ],
  },
  [DatabaseTypeCode.OSCAR]: {
    [TreeNodeType.DATA_SOURCE]: [
      // connection
      OperationColumn.CloseConnection,
      OperationColumn.ApplyPermission,
      OperationColumn.Divider,
      // Operation
      OperationColumn.CreateConsole,
      OperationColumn.RunSqlFile,
      OperationColumn.Divider,
      // Edit & Copy
      OperationColumn.EditSource,
      OperationColumn.CopyDataSource,
      OperationColumn.CopyName,
      OperationColumn.CopyMcpConfig,
      OperationColumn.Divider,
      // Management
      OperationColumn.MoveToGroup,
      OperationColumn.Refresh,
      OperationColumn.Divider,
      OperationColumn.RemoveDataSource,
    ],
  },
  ['DEFAULT']: {
    [TreeNodeType.DATA_SOURCES]: [OperationColumn.CopyGlobalMcpConfig, OperationColumn.Refresh],
    [TreeNodeType.GROUP]: [
      // create.
      OperationColumn.CreateDataSource,
      OperationColumn.CreateGroup,
      OperationColumn.Divider,
      // Editor
      OperationColumn.Rename,
      OperationColumn.CopyName,
      OperationColumn.MoveToGroup,
      OperationColumn.Divider,
      OperationColumn.RemoveGroup,
    ],
    [TreeNodeType.DATA_SOURCE]: [
      // connection
      OperationColumn.CloseConnection,
      OperationColumn.ApplyPermission,
      OperationColumn.Divider,
      // Creation & Operation
      OperationColumn.CreateConsole,
      OperationColumn.CreateDatabase,
      OperationColumn.CreateSchema,
      OperationColumn.RunSqlFile,
      OperationColumn.Divider,
      // Edit & Copy
      OperationColumn.EditSource,
      OperationColumn.CopyDataSource,
      OperationColumn.CopyName,
      OperationColumn.CopyMcpConfig,
      OperationColumn.Divider,
      // Management
      OperationColumn.MoveToGroup,
      OperationColumn.Refresh,
      OperationColumn.Divider,
      OperationColumn.RemoveDataSource,
    ],
    [TreeNodeType.DATABASE_ACCOUNTS]: [OperationColumn.CreateAccount, OperationColumn.Refresh],
    [TreeNodeType.DATABASE_ACCOUNT]: [OperationColumn.OpenAccountPrivileges],
    [TreeNodeType.SCHEMAS]: [],
    [TreeNodeType.ALL_DATA]: [OperationColumn.CreateConsole, OperationColumn.OpenAllData],
    [TreeNodeType.AI_DATA_COLLECTIONS]: [
      OperationColumn.CreateAiDataCollection,
      OperationColumn.CopyName,
      OperationColumn.Refresh,
    ],
    [TreeNodeType.AI_DATA_COLLECTION]: [
      // added
      OperationColumn.AddAiDataCollectionTable,
      OperationColumn.AddAiDataCollectionView,
      OperationColumn.SyncAiDataCollection,
      OperationColumn.Divider,
      // Edit & Copy
      OperationColumn.Rename,
      OperationColumn.CopyName,
      OperationColumn.CopyAiDataCollectionId,
      OperationColumn.Divider,
      OperationColumn.Refresh,
      OperationColumn.Divider,
      OperationColumn.RemoveAiDataCollection,
    ],
    [TreeNodeType.AI_DATA_COLLECTION_TABLE]: [
      OperationColumn.ChangeAiTableInfo,
      OperationColumn.CopyName,
      OperationColumn.Divider,
      OperationColumn.RemoveAiDataCollectionElement,
    ],
    [TreeNodeType.AI_DATA_COLLECTION_VIEW]: [
      OperationColumn.ChangeAiTableInfo,
      OperationColumn.CopyName,
      OperationColumn.Divider,
      OperationColumn.RemoveAiDataCollectionElement,
    ],
    [TreeNodeType.DATABASE]: [
      // Operation
      OperationColumn.CreateConsole,
      OperationColumn.CreateSchema,
      OperationColumn.Divider,
      // SQL & Synchronization
      OperationColumn.RunSqlFile,
      OperationColumn.ExportSqlFile,
      OperationColumn.SchemaSync,
      OperationColumn.Divider,
      // Copy & Tools
      OperationColumn.CopyMcpConfig,
      OperationColumn.CopyName,
      OperationColumn.Refresh,
      OperationColumn.Divider,
      OperationColumn.DeleteDatabase,
    ],
    [TreeNodeType.SCHEMA]: [
      OperationColumn.CreateConsole,
      OperationColumn.Divider,
      OperationColumn.RunSqlFile,
      OperationColumn.ExportSqlFile,
      OperationColumn.SchemaSync,
      OperationColumn.Divider,
      OperationColumn.CopyMcpConfig,
      OperationColumn.CopyName,
      OperationColumn.Refresh,
      OperationColumn.Divider,
      OperationColumn.DeleteSchema,
    ],
    [TreeNodeType.TABLES]: [
      // View
      OperationColumn.CreateConsole,
      OperationColumn.ViewAllTable,
      OperationColumn.ViewERModal,
      OperationColumn.Divider,
      // create.
      OperationColumn.CreateTable,
      OperationColumn.Divider,
      OperationColumn.CopyName,
      OperationColumn.Refresh,
    ],
    [TreeNodeType.TABLE]: [
      // Open & Edit
      OperationColumn.OpenTable,
      OperationColumn.EditTable,
      OperationColumn.CreateConsole,
      OperationColumn.Pin,
      OperationColumn.Divider,
      // Copy & Import and Export
      OperationColumn.CopyName,
      OperationColumn.ViewDDL,
      OperationColumn.CopyTable,
      OperationColumn.ExportSqlFile,
      OperationColumn.ImportData,
      OperationColumn.ExportData,
      OperationColumn.Divider,
      // AI
      OperationColumn.ChangeAiTableInfoNodataCollection,
      OperationColumn.GenerateTestData,
      OperationColumn.Divider,
      // Dangerous operation
      OperationColumn.TruncateTable,
      OperationColumn.DeleteTable,
    ],
    [TreeNodeType.VIEWS]: [
      OperationColumn.CreateConsole,
      OperationColumn.ViewAllView,
      OperationColumn.Divider,
      OperationColumn.CopyName,
      OperationColumn.Refresh,
    ],
    [TreeNodeType.FUNCTIONS]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
    [TreeNodeType.PROCEDURES]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
    [TreeNodeType.TRIGGERS]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
    [TreeNodeType.VIEWCOLUMNS]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
    [TreeNodeType.PROCEDURE]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.OpenProcedure],
    [TreeNodeType.FUNCTION]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.OpenFunction],
    [TreeNodeType.TRIGGER]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.OpenTrigger],
    [TreeNodeType.VIEW]: [
      // Open & Edit
      OperationColumn.OpenView,
      OperationColumn.EditView,
      OperationColumn.CreateConsole,
      OperationColumn.Divider,
      // AI & Copy
      OperationColumn.ChangeAiTableInfoNodataCollection,
      OperationColumn.CopyName,
    ],
    [TreeNodeType.VIEWCOLUMN]: [OperationColumn.CreateConsole, OperationColumn.CopyName],
    [TreeNodeType.COLUMNS]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
    [TreeNodeType.COLUMN]: [OperationColumn.CreateConsole, OperationColumn.CopyName],
    [TreeNodeType.KEYS]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
    [TreeNodeType.KEY]: [OperationColumn.CreateConsole, OperationColumn.CopyName],
    [TreeNodeType.INDEXES]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
    [TreeNodeType.INDEX]: [OperationColumn.CreateConsole, OperationColumn.CopyName],
    [TreeNodeType.SAVE_CONSOLES]: [OperationColumn.CreateConsole, OperationColumn.CopyName, OperationColumn.Refresh],
    [TreeNodeType.SAVE_CONSOLE]: [OperationColumn.OpenConsole, OperationColumn.Rename, OperationColumn.RemoveConsole],
  },
};
