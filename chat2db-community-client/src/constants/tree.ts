export enum TreeNodeType {
  GROUPS = 'groups',
  GROUP = 'group',
  SCHEMAS = 'schemas',
  ALL_DATA = 'allData', // All data dedicated to redis
  DATA_SOURCES = 'dataSources',
  DATA_SOURCE = 'dataSource',
  MONITOR = 'monitor',
  ACTIVE_TRANSACTIONS = 'activeTransactions',
  DATABASE_ACCOUNTS = 'databaseAccounts',
  DATABASE_ACCOUNT = 'databaseAccount',
  DATABASE = 'database',
  SCHEMA = 'schema',
  TABLES = 'tables',
  TABLE = 'table',
  COLUMNS = 'columns',
  COLUMN = 'column',
  KEYS = 'keys',
  KEY = 'key',
  INDEXES = 'indexes',
  INDEX = 'index',
  VIEWS = 'views', // view group
  VIEW = 'view', // view
  VIEWCOLUMN = 'viewColumn',
  VIEWCOLUMNS = 'viewColumns',
  FUNCTIONS = 'functions', // function group
  FUNCTION = 'function', // function
  PROCEDURES = 'procedures', // procedure group
  PROCEDURE = 'procedure', // procedure
  TRIGGERS = 'triggers', // trigger group
  TRIGGER = 'trigger', // trigger
  // Saved console
  SAVE_CONSOLES = 'saveConsoles',
  SAVE_CONSOLE = 'saveConsole',
}

// Functions supported by tree right-click
export enum OperationColumn {
  // Group
  CreateGroup = 'createGroup',
  RemoveGroup = 'removeGroup',
  CopyName = 'copyName',
  // Move to xxx group
  MoveToGroup = 'moveToGroup',
  // CopyMcpConfig
  CopyMcpConfig = 'copyMcpConfig',
  CopyGlobalMcpConfig = 'copyGlobalMcpConfig',

  // open all data
  OpenAllData = 'openAllData',

  // Universal
  DeleteTreeNode = 'deleteTreeNode', // delete tree node
  Refresh = 'refresh', // Refresh menus at all levels
  Partitions = 'partitions', // Partition inspection and maintenance (MYSQL-OBJ-009)
  ActiveTransactions = 'activeTransactions', // Active InnoDB transactions (MYSQL-OPS-002)
  CreateConsole = 'createConsole', // Create a new console
  Rename = 'rename', // Rename

  CreateDataSource = 'createDataSource', // Create a new data source
  OpenAccountPrivileges = 'openAccountPrivileges', // Open database account
  CreateAccount = 'createAccount', // Create a new database account

  RemoveDataSource = 'removeDataSource', // Remove data source
  EditSource = 'editSource', // Edit data source
  SetDataSourceColor = 'setDataSourceColor', // Set the shared data source identity color
  ClearDataSourceColor = 'clearDataSourceColor', // Clear the custom data source identity color
  CopyDataSource = 'copyDataSource', // Copy data source
  CreateTable = 'createTable', //Create table
  DeleteTable = 'deleteTable', // Delete table
  OpenTable = 'openTable', // open table
  ViewDDL = 'viewDDL', // View ddl
  Pin = 'pin', // pin to top
  EditTable = 'editTable', // edit table
  EditTableData = 'editTableData', // Edit table data
  EditView = 'editView', // Edit view
  OpenView = 'openView', // open view
  OpenFunction = 'openFunction', // open function
  OpenProcedure = 'openProcedure', // Open stored procedure
  OpenTrigger = 'openTrigger', // open trigger
  CreateSchema = 'createSchema', // Create new schema
  CreateDatabase = 'createDatabase', // Create a new database
  DeleteSchema = 'deleteSchema', // Delete schema
  DeleteDatabase = 'deleteDatabase', // Delete database
  ViewAllTable = 'viewAllTable', // View all tables
  ViewAllView = 'viewAllView', // View all views
  ViewERModal = 'viewERModal', // View ER diagram
  GenerateCRUD = 'generateCRUD', // Generate CRUD
  // Neutral menu capability slots retained for cross-product menu contracts.
  // Community does not provide AI data-collection implementations.
  CreateAiDataCollection = 'createAiDataCollection',
  RemoveAiDataCollection = 'removeAiDataCollection',
  RemoveAiDataCollectionElement = 'removeAiDataCollectionElement',
  SyncAiDataCollection = 'syncAiDataCollection',
  ChangeAiTableInfo = 'changeAiTableInfo',
  ChangeAiTableInfoNodataCollection = 'changeAiTableInfoNodataCollection',
  OpenConsole = 'openConsole', // open console
  RemoveConsole = 'removeConsole',
  // Run sql file
  RunSqlFile = 'runSqlFile',
  // Export as sql file
  ExportSqlFile = 'exportSqlFile',
  // Export
  ExportData = 'export',
  // import
  ImportData = 'import',
  // Copy table
  CopyTable = 'copyTable',
  // Clear table
  TruncateTable = 'truncateTable',

  // Apply for data source permissions
  ApplyPermission = 'applyPermission',
  // close connection
  CloseConnection = 'closeConnection',

  // Generate java class
  GenerateJavaClass = 'generateJavaClass',
  // Structure synchronization
  SchemaSync = 'schemaSync',
  // Dividing line (only used for right-click menu grouping)
  Divider = '__divider__',
}
