import createRequest from './base';
import { IManageResultData, IEditTableInfo, IDatabaseBaseInfo, IResultCell } from '@/typings';
import { CRUD } from '@/constants';
import type {
  IDdlExecuteRequest,
  ISqlEditorExecuteRequest,
  ITableBrowseRequest,
  ITableEditExecuteRequest,
} from './dmlRequest';

// Modify the sql parameters of the table
export interface IModifyTableSqlParams extends IDatabaseBaseInfo {
  oldTable?: IEditTableInfo;
  newTable: IEditTableInfo;
  refresh: boolean;
}

export interface IUpdateDataSql {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  tableName: string;
  headerList: any;
  operations: {
    type: CRUD;
    dataList: (string | null)[];
  }[];
}

export interface ICopyInValuesSqlParams extends IDatabaseBaseInfo {
  consoleId?: number | string;
  headerList?: any;
  operations?: {
    type: string;
    dataList: (string | null)[];
    selectCols?: number[];
    selectedCell?: IResultCell;
  }[];
  externalValues?: string[];
  sourceType: 'RESULT_SET' | 'EXTERNAL_TEXT';
}

/** Execute SQL */
const executeSql = createRequest<ISqlEditorExecuteRequest, IManageResultData[]>('/api/rdb/dml/execute', {
  method: 'post',
  errorLevel: false,
  timeout: false,
});

/** Get the sql to modify the table */
const getModifyTableSql = createRequest<IModifyTableSqlParams, { sql: string }[]>('/api/rdb/table/modify/sql', {
  method: 'post',
});

/** Execute sql for editing tables, specially designed for editing tables */
const executeDDL = createRequest<IDdlExecuteRequest, { success: boolean; message: string; originalSql: string }>(
  '/api/rdb/dml/execute_ddl',
  { method: 'post' },
);

/** Get the interface for modifying table data */
const getUpdateDataSql = createRequest<any, string>('/api/rdb/dml/get_update_sql', { method: 'post' });

/** Get the interface for modifying table data */
const getCopyUpdateDataSql = createRequest<any, string>('/api/rdb/dml/copy_update_sql', { method: 'post' });

/** Get the list of SQL IN values */
const getCopyInValuesSql = createRequest<ICopyInValuesSqlParams, string>('/api/rdb/dml/copy_in_values_sql', {
  method: 'post',
});

/** Execute sql that modifies table data */
const executeUpdateDataSql = createRequest<
  ITableEditExecuteRequest,
  { success: boolean; message: string; sql: string }
>(
  '/api/rdb/dml/execute_update',
  { method: 'post', errorLevel: false },
);

const viewTable = createRequest<ITableBrowseRequest, IManageResultData[]>('/api/rdb/dml/execute_table', {
  method: 'post',
  errorLevel: false,
});

export default {
  executeSql,
  executeDDL,
  getModifyTableSql,
  getUpdateDataSql,
  getCopyUpdateDataSql,
  getCopyInValuesSql,
  executeUpdateDataSql,
  viewTable,
};
