import { DatabaseTypeCode } from '@/constants';
import {
  IDatabaseSupportField,
  IEditTableInfo,
  IManageResultData,
  IPageParams,
  IPageResponse,
  IRoutines,
  ITable,
  IUniversalTableParams,
} from '@/typings';
import { ISchemaCompareParams, ISchemaDiffResult, ISchemaMigrateParams, IMigrateResult } from '@/typings/schemaDiff';
import { ExportSizeEnum, ExportTypeEnum } from '@/typings/resultTable';
import createRequest from './base';

export interface ITreeSearchParams extends IPageParams {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  searchKey?: string;
  treeNodeType?: string;
  refresh?: boolean;
}

export interface ITreeNodeResponse {
  uuid: string;
  key: string;
  name: string;
  treeNodeType: string;
  pretendNodeType?: string;
  comment?: string;
  isLeaf?: boolean;
  pinned?: boolean;
  parentPath?: string[];
  extraParams?: Record<string, any>;
}

const searchTree = createRequest<ITreeSearchParams, ITreeNodeResponse[]>('/api/rdb/tree/search', { method: 'get' });

export interface IGetTableListParams extends IPageParams {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  databaseType?: DatabaseTypeCode;
}

export interface IExecuteSqlParams {
  sql?: string;
  scriptStartLine?: number;
  consoleId?: number;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string | null;
  tableName?: string;
  pageNo?: number;
  pageSize?: number;
}

export interface IExecuteSqlResponse {
  sql: string;
  description: string;
  message: string;
  success: boolean;
  headerList: any[];
  dataList: any[];
}
export interface IConnectConsoleParams {
  consoleId: number;
  dataSourceId: number;
  databaseName: string;
}

/** ER图节点，代表一张数据库表 */
export interface IErNode {
  /** 节点唯一标识 */
  id: string;
  /** 表名 */
  name: string;
  /** 表注释 */
  comment?: string;
  /** 表的列数量 */
  columnCount?: number;
}

/** ER图边，代表表之间的外键关系 */
export interface IErEdge {
  /** 边唯一标识 */
  id: string;
  /** 源表名（拥有外键的表） */
  source: string;
  /** 目标表名（被引用的表） */
  target: string;
  /** 源表的外键列名 */
  sourceColumn: string;
  /** 目标表被引用的列名 */
  targetColumn: string;
  /** 关系描述 */
  label: string;
  /** 是否为虚拟外键 */
  virtual: boolean;
}

/** ER图数据，包含节点和边 */
export interface IErDiagram {
  nodes: IErNode[];
  edges: IErEdge[];
}

/** ER图查询参数 */
export interface IErParams {
  /** 数据源ID */
  dataSourceId: number;
  /** 数据库名 */
  databaseName: string;
  /** Schema名 */
  schemaName?: string;
  /** 表名过滤条件 */
  tableNameFilter?: string;
  /** 是否包含虚拟外键 */
  includeVirtualFk?: boolean;
  /** 是否同步数据库真实外键到本地 */
  syncForeignKeys?: boolean;
  /** 是否只显示关联表 */
  onlyRelatedTables?: boolean;
}

/** 获取ER图数据接口 */
const getErDiagram = createRequest<IErParams, IErDiagram>('/api/rdb/er/diagram', { method: 'get' });

const getTableList = createRequest<IGetTableListParams, IPageResponse<ITable>>('/api/rdb/table/list', {
  method: 'get',
});

const executeSql = createRequest<IExecuteSqlParams, IManageResultData[]>('/api/rdb/dml/execute', {
  method: 'post',
  delayTime: 10,
});

const viewTable = createRequest<IExecuteSqlParams, IManageResultData[]>('/api/rdb/dml/execute_table', {
  method: 'post',
  delayTime: 10,
});

const connectConsole = createRequest<IConnectConsoleParams, void>('/api/connection/console/connect', { method: 'get' });

//表操作
export interface ITableParams {
  tableName: string;
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
}

export interface IExecuteTableParams {
  sql: string;
  consoleId: number;
  dataSourceId: number;
  databaseName: string;
}

export interface IColumn {
  name: string;
  dataType: string;
  columnType: string; // 列的类型 比如 varchar(100) ,double(10,6)
  nullable: boolean;
  primaryKey: boolean;
  defaultValue: string;
  autoIncrement: boolean;
  numericPrecision: number;
  numericScale: number;
  characterMaximumLength: number;
  comment: string;
}

/** 外键定义接口 */
export interface IForeignKey {
  name: string; // 外键名称
  referencedTable: string; // 引用的表名
  referencedColumn: string; // 引用的列名
  updateRule: number; // 更新规则
  deleteRule: number; // 删除规则
  comment?: string; // 备注（可选）
  isNullable: boolean; // 是否允许为空
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

export interface IFunctionCall {
  name?: string;
  arguments?: string;
}

export interface IToolCall {
  id?: string;
  type?: string;
  function?: IFunctionCall;
}

export interface IMessage {
  role?: string;
  tool_calls?: IToolCall[];
}

const deleteTable = createRequest<ITableParams, void>('/api/rdb/table/delete', { method: 'post' });
const deleteDatabase = createRequest<{ dataSourceId: number; databaseName: string }, void>(
  '/api/rdb/database/delete_database',
  { method: 'post' },
);
const truncateTable = createRequest<ITableParams, void>('/api/rdb/table/truncate', { method: 'post' });
const createTableExample = createRequest<{ dbType: DatabaseTypeCode }, string>('/api/rdb/table/create/example', {
  method: 'get',
});
const updateTableExample = createRequest<{ dbType: DatabaseTypeCode }, string>('/api/rdb/table/update/example', {
  method: 'get',
});
const exportCreateTableSql = createRequest<ITableParams, string>('/api/rdb/table/export', { method: 'get' });
const executeTable = createRequest<IExecuteTableParams, string>('/api/rdb/dml/execute', { method: 'post' });

const getColumnList = createRequest<ITableParams, IColumn[]>('/api/rdb/table/column_list', {
  method: 'get',
  delayTime: 200,
});
const getIndexList = createRequest<ITableParams, IColumn[]>('/api/rdb/table/index_list', {
  method: 'get',
  delayTime: 200,
});
const getKeyList = createRequest<ITableParams, IForeignKey[]>('/api/rdb/fk/list', { method: 'get', delayTime: 200 });
const getSchemaList = createRequest<ISchemaParams, ISchemaResponse[]>('/api/rdb/schema/list', {
  method: 'get',
  delayTime: 200,
});

const getDatabaseSchemaList = createRequest<{ dataSourceId: number }, MetaSchemaVO>(
  '/api/rdb/database/database_schema_list',
  { method: 'get' },
);

const addTablePin = createRequest<IUniversalTableParams, void>('/api/pin/table/add', { method: 'post' });

const deleteTablePin = createRequest<IUniversalTableParams, void>('/api/pin/table/delete', { method: 'post' });

const deprecatedTable = createRequest<IUniversalTableParams, void>('/api/rdb/table/deprecated', { method: 'post' });

const restoreDeprecatedTable = createRequest<IUniversalTableParams, void>('/api/rdb/table/cancel_deprecated', {
  method: 'post',
});

const getDeprecatedTableList = createRequest<IGetTableListParams, ITable[]>('/api/rdb/table/deprecated_list', {
  method: 'get',
});

/** 获取当前执行SQL 所有行 */
const getDMLCount = createRequest<IExecuteSqlParams, number>('/api/rdb/dml/count', { method: 'post' });

export interface IExportParams extends IExecuteSqlParams {
  originalSql: string;
  exportType: ExportTypeEnum;
  exportSize: ExportSizeEnum;
}
/**
 * 导出-表格
 */
// const exportResultTable = createRequest<IExportParams, any>('/api/rdb/dml/export', { method: 'post' });

/** 获取视图列表 */
const getViewList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/view/list', {
  method: 'get',
});

/** 获取函数列表 */
const getFunctionList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/function/list', {
  method: 'get',
});

/** 获取触发器列表 */
const getTriggerList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/trigger/list', {
  method: 'get',
});

/** 获取过程列表 */
const getProcedureList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/procedure/list', {
  method: 'get',
});

/** 获取视图列列表 */
const getViewColumnList = createRequest<IGetTableListParams, IPageResponse<IRoutines>>('/api/rdb/view/column_list', {
  method: 'get',
});

/** 获取视图详情 */
const getViewDetail = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    tableName: string;
  },
  { ddl: string }
>('/api/rdb/view/detail', { method: 'get' });

/** 获取触发器详情 */
const getTriggerDetail = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    triggerName: string;
  },
  { triggerBody: string }
>('/api/rdb/trigger/detail', { method: 'get' });

/** 获取函数详情 */
const getFunctionDetail = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    functionName: string;
  },
  { functionBody: string }
>('/api/rdb/function/detail', { method: 'get' });

/** 获取过程详情 */
const getProcedureDetail = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    procedureName: string;
  },
  { procedureBody: string }
>('/api/rdb/procedure/detail', { method: 'get' });

/** 格式化sql */
const sqlFormat = createRequest<
  {
    sql: string;
    dbType: DatabaseTypeCode;
  },
  string
>('/api/sql/format', { method: 'get' });

/** 数据库支持的数据类型 */
const getDatabaseFieldTypeList = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
  },
  IDatabaseSupportField
>('/api/rdb/table/table_meta', { method: 'get' });

/** 获取表的详情 */
const getTableDetails = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string | null;
    tableName: string;
    refresh: boolean;
  },
  IEditTableInfo
>('/api/rdb/table/query', { method: 'get' });

/** 获取库的所有表 */
const getAllTableList = createRequest<
  { dataSourceId: number; databaseName?: string | null; schemaName?: string | null },
  Array<{ name: string; comment: string }>
>('/api/rdb/table/table_list', { method: 'get' });

/** 获取表的所有字段 */
const getAllFieldByTable = createRequest<
  { dataSourceId: number; databaseName?: string; schemaName?: string | null; tableName: string },
  Array<{ name: string; tableName: string }>
>('/api/rdb/table/column_list', { method: 'get' });

const getAiGuess = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string | null | undefined;
    tableNames: string[];
    promptType: string;
  },
  IMessage
>('/api/ai/er/guess', {
  method: 'get',
});

export interface IModifyTableSqlParams {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string | null;
  tableName?: string;
  oldTable?: IEditTableInfo;
  newTable: IEditTableInfo;
  refresh: boolean;
}
export interface IBatchModifyTableSqlParams {
  dataSourceId: string;
  databaseName: string;
  schemaName?: string | null;
  tableName?: string;
  oldTables?: any[];
  newTables: any[];
  refresh: boolean;
}

/** 获取修改表的sql */
const getModifyTableSql = createRequest<IModifyTableSqlParams, { sql: string }[]>('/api/rdb/table/modify/sql', {
  method: 'post',
});
/** 定义批量获取修改表的SQL语句的API接口 */
const getBatchModifyTableSql = createRequest<IBatchModifyTableSqlParams, { sql: string }[]>(
  '/api/rdb/table/batch/modify/sql',
  {
    method: 'post',
  },
);
/** 执行编辑表的sql, 专为编辑表而生 */
const executeDDL = createRequest<IExecuteSqlParams, { success: boolean; message: string; originalSql: string }>(
  '/api/rdb/dml/execute_ddl',
  { method: 'post' },
);

// 执行修改表数据的sql
const executeUpdateDataSql = createRequest<IExecuteSqlParams, { success: boolean; message: string; sql: string }>(
  '/api/rdb/dml/execute_update',
  { method: 'post' },
);

/** 获取修改表数据的接口 */
const getExecuteUpdateSql = createRequest<any, string>('/api/rdb/dml/get_update_sql', { method: 'post' });

/** 创建数据库  */
const getCreateDatabaseSql = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
  },
  { sql: string }
>('/api/rdb/database/create_database_sql', { method: 'post' });

/** 创建schema  */
const getCreateSchemaSql = createRequest<
  {
    dataSourceId: number;
    databaseName?: string;
    schemaName?: string;
  },
  { sql: string }
>('/api/rdb/schema/create_schema_sql', { method: 'post' });

const deleteVirtualForeignKey = createRequest<
  {
    dataSourceId: number;
    databaseName: string;
    schemaName?: string;
    tableName: string;
    keyName: string;
  },
  void
>('/api/rdb/fk/delete_by_name', { method: 'post' });

/** 外键列表查询参数 */
export interface IForeignKeyListParams {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
}

/** 外键列表响应 */
export interface IForeignKeyVO {
  id?: number;
  name: string;
  tableName: string;
  columnName: string;
  referencedTable: string;
  referencedColumnName: string;
  comment?: string;
  updateRule: number;
  deleteRule: number;
  sourceType: 'REAL' | 'VIRTUAL';
  editable: boolean;
  virtualProperty?: string;
}

/** 外键同步参数 */
export interface IForeignKeySyncParams {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
}

/** 外键同步结果 */
export interface ISyncResult {
  added: number;
  deleted: number;
  unchanged: number;
}

/** 创建虚拟外键参数 */
export interface ICreateVirtualFKParams {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  tableName: string;
  columnName: string;
  referencedTable: string;
  referencedColumnName: string;
  comment?: string;
}

/** 更新虚拟外键参数 */
export interface IUpdateVirtualFKParams {
  id: number;
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  vkName?: string;
  tableName?: string;
  columnName?: string;
  referencedTable?: string;
  referencedColumnName?: string;
  comment?: string;
}

/** 删除外键参数 */
export interface IDeleteFKParams {
  id: number;
  sourceType: 'REAL' | 'VIRTUAL';
}

/** 删除外键结果 */
export interface IDeleteFKResult {
  executedDDL: string | null;
}

/** 虚拟外键推断参数 */
export interface IInferVirtualFKParams {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  tableNameFilter?: string;
}

/** 虚拟外键推断结果项 */
export interface IInferVirtualFkItem {
  tableName: string;
  columnName: string;
  referencedTable: string;
  referencedColumnName: string;
}

/** 虚拟外键推断结果 */
export interface IInferVirtualFkResult {
  addedCount: number;
  deletedCount: number;
  added: IInferVirtualFkItem[];
  deleted: IInferVirtualFkItem[];
}

const syncForeignKeys = createRequest<IForeignKeySyncParams, ISyncResult>('/api/rdb/fk/sync', { method: 'post' });
const getForeignKeyList = createRequest<IForeignKeyListParams, IForeignKeyVO[]>('/api/rdb/fk/list', { method: 'get' });
const createVirtualForeignKey = createRequest<ICreateVirtualFKParams, IForeignKey>('/api/rdb/fk/virtual/create', {
  method: 'post',
});
const updateVirtualForeignKey = createRequest<IUpdateVirtualFKParams, IForeignKey>('/api/rdb/fk/virtual/update', {
  method: 'post',
});
const deleteForeignKey = createRequest<IDeleteFKParams, IDeleteFKResult>('/api/rdb/fk/delete', { method: 'post' });
const inferVirtualForeignKeys = createRequest<IInferVirtualFKParams, IInferVirtualFkResult>(
  '/api/rdb/er/infer-virtual-fk',
  { method: 'post' },
);

const batchOptimizeTables = createRequest<
  {
    dataSourceId: number;
    databaseName?: string;
    schemaName?: string;
    tableNames: string[];
  },
  any[]
>('/api/rdb/table/batch/optimize', { method: 'post' });

const batchAnalyzeTables = createRequest<
  {
    dataSourceId: number;
    databaseName?: string;
    schemaName?: string;
    tableNames: string[];
  },
  any[]
>('/api/rdb/table/batch/analyze', { method: 'post' });

const compareSchema = createRequest<ISchemaCompareParams, ISchemaDiffResult>('/api/rdb/schema/diff/compare', {
  method: 'post',
});

const migrateSchema = createRequest<ISchemaMigrateParams, IMigrateResult>('/api/rdb/schema/diff/migrate', {
  method: 'post',
});

export default {
  searchTree,
  getCreateSchemaSql,
  getCreateDatabaseSql,
  executeUpdateDataSql,
  executeDDL,
  getExecuteUpdateSql,
  getModifyTableSql,
  getBatchModifyTableSql,
  getTableDetails,
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
  getErDiagram,
  executeSql,
  executeTable,
  connectConsole,
  deleteTable,
  deleteDatabase,
  createTableExample,
  updateTableExample,
  exportCreateTableSql,
  viewTable,
  getColumnList,
  getIndexList,
  getKeyList,
  getForeignKeyList,
  getSchemaList,
  getDatabaseSchemaList,
  addTablePin,
  deleteTablePin,
  deprecatedTable,
  restoreDeprecatedTable,
  getDeprecatedTableList,
  getDMLCount,
  // exportResultTable
  getAllTableList,
  getAllFieldByTable,
  getAiGuess,
  deleteVirtualForeignKey,
  truncateTable,
  inferVirtualForeignKeys,
  createVirtualForeignKey,
  deleteForeignKey,
  updateVirtualForeignKey,
  syncForeignKeys,
  batchOptimizeTables,
  batchAnalyzeTables,
  compareSchema,
  migrateSchema,
};
