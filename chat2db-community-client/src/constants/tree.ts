export enum TreeNodeType {
  GROUPS = 'groups',
  GROUP = 'group',
  SCHEMAS = 'schemas',
  AI_DATA_COLLECTIONS = 'aiDataCollections',
  AI_DATA_COLLECTION = 'aiDataCollection',
  AI_DATA_COLLECTION_TABLE = 'aiDataCollectionTable',
  AI_DATA_COLLECTION_VIEW = 'aiDataCollectionView',
  ALL_DATA = 'allData', // All data dedicated to redis
  DATA_SOURCES = 'dataSources',
  DATA_SOURCE = 'dataSource',
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
  // Create AI dataset
  CreateAiDataCollection = 'createAiDataCollection',
  // Delete AI dataset
  RemoveAiDataCollection = 'removeAiDataCollection',
  // Delete a table from a collection
  RemoveAiDataCollectionElement = 'removeAiDataCollectionElement',
  // Resynchronize AI datasets
  SyncAiDataCollection = 'syncAiDataCollection',
  // Rename
  RenameAiDataCollection = 'renameAiDataCollection',
  // Add a table to an AI dataset
  AddAiDataCollectionTable = 'addAiDataCollectionTable',
  // Add views to AI datasets
  AddAiDataCollectionView = 'addAiDataCollectionView',
  // Copy the AI dataset ID.
  CopyAiDataCollectionId = 'copyAiDataId',
  // CopyMcpConfig
  CopyMcpConfig = 'copyMcpConfig',
  CopyGlobalMcpConfig = 'copyGlobalMcpConfig',

  // Modify the table structure of AI
  ChangeAiTableInfo = 'changeAiTableInfo',
  ChangeAiTableInfoNodataCollection = 'changeAiTableInfoNodataCollection',

  // open all data
  OpenAllData = 'openAllData',

  // Universal
  DeleteTreeNode = 'deleteTreeNode', // delete tree node
  Refresh = 'refresh', // Refresh menus at all levels
  CreateConsole = 'createConsole', // Create a new console
  Rename = 'rename', // Rename

  CreateDataSource = 'createDataSource', // Create a new data source
  OpenAccountPrivileges = 'openAccountPrivileges', // Open database account
  CreateAccount = 'createAccount', // Create a new database account

  RemoveDataSource = 'removeDataSource', // Remove data source
  EditSource = 'editSource', // Edit data source
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
  GenerateTestData = 'generateTestData', // Generate test data
  AnalyzeTable = 'analyzeTable', // Analyze table
  OptimizeTable = 'optimizeTable', // Optimize table
  CheckTable = 'checkTable', // Check table
  RepairTable = 'repairTable', // Repair table
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
