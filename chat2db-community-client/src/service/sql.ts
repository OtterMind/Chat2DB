import createRequest from './base';
import {
  IPageResponse,
  IPageParams,
  IUniversalTableParams,
  IManageResultData,
  ILargeCellChunk,
  ILargeCellDownloadRequest,
  ILargeCellValueRequest,
  IRoutines,
  IDatabaseSupportField,
  IEditTableInfo,
  ITable,
  IConnectionDetails,
} from '@/typings';
import { DatabaseTypeCode } from '@/constants';
import { ExportSizeEnum, ExportTypeEnum } from '@/typings/resultTable';
import type {
  ActiveTransactionLockMetadataSource,
  ActiveTransactionLockMetadataState,
  ActiveTransactionQueryState,
  ActiveTransactionSessionState,
} from '@/constants/activeTransaction';
import type {
  IDdlExecuteRequest,
  ISqlEditorExecuteRequest,
  ITableBrowseRequest,
  ITableEditExecuteRequest,
} from './dmlRequest';

export interface IGetTableListParams extends IPageParams {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  databaseType?: DatabaseTypeCode;
}

interface IDmlResultRequest {
  sql?: string;
  single?: boolean;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string | null;
  tableName?: string;
  pageNo?: number;
  pageSize?: number;
  errorContinue?: boolean;
}

export interface IExecuteSqlResponse {
  sql: string;
  description: string;
  message: string;
  success: boolean;
  headerList: any[];
  dataList: any[];
}
export interface IRoutineOperationParams {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string | null;
  routineType: 'FUNCTION' | 'PROCEDURE';
  routineName: string;
}

export interface IRoutineMigrationParams extends IRoutineOperationParams {
  ddl: string;
}

export interface IRoutineOperationPreview {
  sql: string;
}

const getTableList = createRequest<IGetTableListParams, IPageResponse<ITable>>('/api/rdb/table/list', {
  method: 'get',
  errorLevel: false,
});

const executeSql = createRequest<ISqlEditorExecuteRequest, IManageResultData[]>('/api/rdb/dml/execute', {
  method: 'post',
  errorLevel: false,
  timeout: false,
});

const viewTable = createRequest<ITableBrowseRequest, IManageResultData[]>('/api/rdb/dml/execute_table', {
  method: 'post',
  errorLevel: false,
});

const getLargeCellValue = createRequest<ILargeCellValueRequest, ILargeCellChunk>('/api/rdb/cell/value', {
  method: 'post',
  errorLevel: 'toast',
});

const downloadLargeCellValue = createRequest<ILargeCellDownloadRequest, string>('/api/rdb/cell/download_path', {
  method: 'post',
  errorLevel: 'toast',
});

//Table operations
export interface ITableParams {
  tableName: string;
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
}

export interface IColumn {
  name: string;
  dataType: string;
  columnType: string; // Column type, such as varchar(100), double(10,6)
  nullable: boolean;
  primaryKey: boolean;
  defaultValue: string;
  autoIncrement: boolean;
  numericPrecision: number;
  numericScale: number;
  characterMaximumLength: number;
  comment: string;
}

interface ICountedListResponse<T> {
  data: T[];
  total?: number | null;
}

export interface ISchemaParams {
  dataSourceId: number;
  databaseName: string;
}
export interface ISchemaResponse {
  name: string;
}

export interface MetaSchemaVO {
  databases?: Database[];
  schemas?: Schema[];
}

export interface Database {
  name: string;
  schemas?: Schema[];
}

export interface Schema {
  name: string;
}

export interface IDatabaseDeletePrepareParams {
  dataSourceId: number;
  databaseName: string;
}

export interface ISchemaDeletePrepareParams {
  dataSourceId: number;
  databaseName: string;
  schemaName: string;
}

export interface IDatabaseObjectDeleteExecuteParams {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  confirmName: string;
}

export interface IDatabaseObjectDeletePrepareVO {
  confirmName: string;
  sqlPreview: string;
  objectType: 'DATABASE' | 'SCHEMA';
  dbType: string;
}

const deleteTable = createRequest<ITableParams, void>('/api/rdb/ddl/delete', { method: 'post' });

const prepareDeleteDatabase = createRequest<IDatabaseDeletePrepareParams, IDatabaseObjectDeletePrepareVO>(
  '/api/rdb/delete/database/prepare',
  { method: 'post', errorLevel: 'toast' },
);

const executeDeleteDatabase = createRequest<IDatabaseObjectDeleteExecuteParams, void>(
  '/api/rdb/delete/database/execute',
  { method: 'post', errorLevel: 'toast' },
);

const prepareDeleteSchema = createRequest<ISchemaDeletePrepareParams, IDatabaseObjectDeletePrepareVO>(
  '/api/rdb/delete/schema/prepare',
  { method: 'post', errorLevel: 'toast' },
);

const executeDeleteSchema = createRequest<IDatabaseObjectDeleteExecuteParams, void>('/api/rdb/delete/schema/execute', {
  method: 'post',
  errorLevel: 'toast',
});

const createTableExample = createRequest<{ dbType: DatabaseTypeCode }, string>('/api/rdb/ddl/create/example', {
  method: 'get',
});
const updateTableExample = createRequest<{ dbType: DatabaseTypeCode }, string>('/api/rdb/ddl/update/example', {
  method: 'get',
});
const exportCreateTableSql = createRequest<ITableParams, string>('/api/rdb/ddl/export', { method: 'get' });

const getColumnList = createRequest<ITableParams, ICountedListResponse<IColumn>>('/api/rdb/ddl/column_list', {
  method: 'get',
  delayTime: 200,
  fullResponse: true,
});
const getIndexList = createRequest<ITableParams, ICountedListResponse<IColumn>>('/api/rdb/ddl/index_list', {
  method: 'get',
  delayTime: 200,
  fullResponse: true,
});
const getKeyList = createRequest<ITableParams, ICountedListResponse<IColumn>>('/api/rdb/ddl/key_list', {
  method: 'get',
  delayTime: 200,
  fullResponse: true,
});
const getSchemaList = createRequest<ISchemaParams, ISchemaResponse[]>('/api/rdb/ddl/schema_list', {
  method: 'get',
  delayTime: 200,
});

const getDatabaseSchemaList = createRequest<{ dataSourceId: number }, MetaSchemaVO>(
  '/api/rdb/ddl/database_schema_list',
  { method: 'get' },
);

const addTablePin = createRequest<IUniversalTableParams, void>('/api/pin/table/add', { method: 'post' });

const deleteTablePin = createRequest<IUniversalTableParams, void>('/api/pin/table/delete', { method: 'post' });

/** Get all rows of currently executing SQL */
const getDMLCount = createRequest<IDmlResultRequest, number>('/api/rdb/dml/count', { method: 'post' });

export interface IExportParams extends IDmlResultRequest {
  originalSql: string;
  exportType: ExportTypeEnum;
  exportSize: ExportSizeEnum;
}
/** Get the view list */
const getViewList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/view/list', {
  method: 'get',
});

/** Get function list */
const getFunctionList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/function/list', {
  method: 'get',
});

/** Get the trigger list */
const getTriggerList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/trigger/list', {
  method: 'get',
});

/** Get process list */
const getProcedureList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/procedure/list', {
  method: 'get',
});

/** Get the view column list */
const getViewColumnList = createRequest<IGetTableListParams, ICountedListResponse<IRoutines>>(
  '/api/rdb/view/column_list',
  {
    method: 'get',
    fullResponse: true,
  },
);

/** Get view details */
const getViewDetail = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    tableName: string;
  },
  { ddl: string }
>('/api/rdb/view/detail', { method: 'get' });

/** Get trigger details */
const getTriggerDetail = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    triggerName: string;
  },
  { triggerBody: string }
>('/api/rdb/trigger/detail', { method: 'get' });

/** Get function details */
const getFunctionDetail = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    functionName: string;
  },
  { functionBody: string }
>('/api/rdb/function/detail', { method: 'get' });

/** Get process details */
const getProcedureDetail = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    procedureName: string;
  },
  { procedureBody: string }
>('/api/rdb/procedure/detail', { method: 'get' });

/** Format sql */
const sqlFormat = createRequest<
  {
    sql: string;
    dbType?: DatabaseTypeCode;
  },
  string
>('/api/sql/format', { method: 'get' });

/** Data types supported by the database */
const getDatabaseFieldTypeList = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
  },
  IDatabaseSupportField
>('/api/rdb/table/table_meta', { method: 'get' });

/** Get table details */
const getTableDetails = createRequest<
  {
    dataSourceId: number;
    databaseName?: string;
    schemaName?: string | null;
    tableName: string;
    refresh: boolean;
  },
  IEditTableInfo
>('/api/rdb/table/query', { method: 'get' });

/** Get view details */
const getViewDetails = createRequest<
  { dataSourceId: number; databaseName?: string; schemaName?: string; tableName: string },
  IEditTableInfo
>('/api/rdb/view/query', { method: 'get' });

/** Get all tables in the library */
const getAllTableList = createRequest<
  { dataSourceId: number; databaseName?: string | null; schemaName?: string | null },
  Array<{ name: string; comment: string }>
>('/api/rdb/table/table_list', { method: 'get', errorLevel: false, permissionError: false });

/** Get all fields of the table */
const getAllFieldByTable = createRequest<
  { dataSourceId: number; databaseName?: string; schemaName?: string | null; tableName: string },
  Array<{ name: string; tableName: string }>
>('/api/rdb/table/column_list', { method: 'get', errorLevel: false });

export interface IModifyTableSqlParams {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string | null;
  tableName?: string;
  oldTable?: IEditTableInfo;
  newTable: IEditTableInfo;
  refresh: boolean;
}

/** Get the sql to modify the table */
const getModifyTableSql = createRequest<IModifyTableSqlParams, { sql: string }[]>('/api/rdb/table/modify/sql', {
  method: 'post',
});

/** Execute sql for editing tables, specially designed for editing tables */
const executeDDL = createRequest<IDdlExecuteRequest, { success: boolean; message: string; originalSql: string }>(
  '/api/rdb/dml/execute_ddl',
  { method: 'post' },
);

const previewRoutineInvocation = createRequest<IRoutineOperationParams, IRoutineOperationPreview>(
  '/api/rdb/routine/preview_invocation',
  { method: 'post', errorLevel: 'toast' },
);

const previewRoutineMigration = createRequest<IRoutineMigrationParams, IRoutineOperationPreview>(
  '/api/rdb/routine/preview_migration',
  { method: 'post', errorLevel: 'toast' },
);

const executeRoutineMigration = createRequest<IRoutineMigrationParams, { success: boolean; message: string }>(
  '/api/rdb/routine/execute_migration',
  { method: 'post', errorLevel: 'toast' },
);

// Execute sql that modifies table data
const executeUpdateDataSql = createRequest<
  ITableEditExecuteRequest,
  { success: boolean; message: string; sql: string }
>('/api/rdb/dml/execute_update', { method: 'post', errorLevel: false });

/** Get the interface for modifying table data */
const getExecuteUpdateSql = createRequest<any, string>('/api/rdb/dml/get_update_sql', { method: 'post' });

/** Create database */
const getCreateDatabaseSql = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    charset?: string;
    collation?: string;
  },
  { sql: string }
>('/api/rdb/database/create_database_sql', { method: 'post' });

/** Create schema */
const getCreateSchemaSql = createRequest<
  {
    dataSourceId: number;
    databaseName?: string;
    schemaName?: string;
  },
  { sql: string }
>('/api/rdb/schema/create_schema_sql', { method: 'post' });

// Clear table data
const truncateTable = createRequest<ITableParams, void>('/api/rdb/table/truncate', { method: 'post' });

export interface ICopyTableParams extends ITableParams {
  copyData: boolean;
}

// Copy table
const copyTable = createRequest<ICopyTableParams, void>('/api/rdb/table/copy', { method: 'post' });


/** Variables / status (MYSQL-OPS-004). */
export interface IVariableItem {
  name: string;
  value: string | null;
  source?: string | null;
  path?: string | null;
  minValue?: string | null;
  maxValue?: string | null;
}

export interface IVariableEditMeta {
  name: string;
  type: 'STRING' | 'NUMBER' | 'ONOFF';
  dynamicScopes: Array<'SESSION' | 'GLOBAL'>;
  persistScopes: Array<'PERSIST' | 'PERSIST_ONLY'>;
  highRisk: boolean;
  source?: string | null;
  path?: string | null;
  minValue?: string | null;
  maxValue?: string | null;
}

interface IVariableSessionContext {
  dataSourceId: number;
  consoleId: number;
}

const getVariableList = createRequest<IVariableSessionContext & { scope: string; kind: string }, IVariableItem[]>(
  '/api/rdb/variable/list',
  { method: 'get' },
);

const getVariableEditable = createRequest<IVariableSessionContext & { name: string }, IVariableEditMeta | null>(
  '/api/rdb/variable/editable',
  { method: 'get' },
);

const previewSetVariableSql = createRequest<
  IVariableSessionContext & { variableName: string; value: string; scope: string },
  string
>('/api/rdb/variable/set_preview', { method: 'post' });
const closeVariableSession = createRequest<IVariableSessionContext, boolean>('/api/rdb/variable/session/close', {
  method: 'post',
  errorLevel: false,
});

/** Active InnoDB transactions (MYSQL-OPS-002). */
export interface IActiveTransactionItem {
  trxId: string | null;
  state: string | null;
  startedAt: number | string | null;
  ageSeconds: number | null;
  isolationLevel: string | null;
  rowsLocked: number | null;
  rowsModified: number | null;
  lockStructs: number | null;
  threadId: number | null;
  user: string | null;
  host: string | null;
  db: string | null;
  query: string | null;
  queryState?: ActiveTransactionQueryState;
  sessionAvailable?: boolean;
  sessionState?: ActiveTransactionSessionState;
  canOpenSession?: boolean;
  connectionInspectionSql: string | null;
  waitingLockId?: string | null;
  blockingLockId?: string | null;
  blockingTrxId?: string | null;
  waitingPerformanceSchemaThreadId?: number | null;
  blockingPerformanceSchemaThreadId?: number | null;
  blockingThreadId?: number | null;
  blockingSessionAvailable?: boolean;
  canOpenBlockingSession?: boolean;
  blockingConnectionInspectionSql: string | null;
  blockingUser?: string | null;
  blockingHost?: string | null;
  blockingDb?: string | null;
  waitingObject?: string | null;
  waitingIndex?: string | null;
  waitingLockType?: string | null;
  waitingLockMode?: string | null;
  waitingLockStatus?: string | null;
  waitingLockData?: string | null;
  blockingObject?: string | null;
  blockingIndex?: string | null;
  blockingLockType?: string | null;
  blockingLockMode?: string | null;
  blockingLockStatus?: string | null;
  blockingLockData?: string | null;
  lockWaitAvailable?: boolean;
  lockMetadataState?: ActiveTransactionLockMetadataState;
  lockMetadataSource?: ActiveTransactionLockMetadataSource | null;
}

export interface IActiveTransactionRequest {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
}

const getActiveTransactionList = createRequest<IActiveTransactionRequest, IActiveTransactionItem[]>(
  '/api/rdb/active_transaction/list',
  { method: 'get', errorLevel: false },
);
const checkIsSelectSQL = createRequest<{ sql: string; dbType: DatabaseTypeCode }, boolean>('/api/sql/valid_select');

const getDataSourceList = createRequest<IPageParams, IPageResponse<IConnectionDetails>>(
  '/api/connection/datasource/list',
  {
    errorLevel: false,
  },
);

export default {
  copyTable,
  downloadLargeCellValue,
  getLargeCellValue,
  truncateTable,
  getCreateSchemaSql,
  getCreateDatabaseSql,
  executeUpdateDataSql,
  executeDDL,
  previewRoutineInvocation,
  previewRoutineMigration,
  executeRoutineMigration,
  getExecuteUpdateSql,
  getModifyTableSql,
  getTableDetails,
  getViewDetails,
  getDatabaseFieldTypeList,
  sqlFormat,
  getTriggerDetail,
  getProcedureDetail,
  getFunctionDetail,
  getViewDetail,
  getViewColumnList,
  getProcedureList,
  getTriggerList,
  getFunctionList,
  getViewList,
  getTableList,
  executeSql,
  deleteTable,
  prepareDeleteDatabase,
  executeDeleteDatabase,
  prepareDeleteSchema,
  executeDeleteSchema,
  createTableExample,
  updateTableExample,
  exportCreateTableSql,
  viewTable,
  getColumnList,
  getIndexList,
  getKeyList,
  getSchemaList,
  getDatabaseSchemaList,
  addTablePin,
  deleteTablePin,
  getDMLCount,
  getAllTableList,
  getAllFieldByTable,
  checkIsSelectSQL,
  getVariableList,
  getVariableEditable,
  previewSetVariableSql,
  closeVariableSession,
  getActiveTransactionList,
  getDataSourceList,
};
