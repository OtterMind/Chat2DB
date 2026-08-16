export interface IDataSourceExecutionContext {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string | null;
}

export interface ISqlEditorExecuteRequest extends IDataSourceExecutionContext {
  sql: string;
  consoleId?: number;
  applyId?: number;
  pageNo?: number;
  pageSize?: number;
  single?: boolean;
  resultSetId?: number;
  errorContinue?: boolean;
  explain?: boolean;
}

export interface ITableBrowseRequest extends IDataSourceExecutionContext {
  tableName: string;
  pageNo?: number;
  pageSize?: number;
}

export interface ITableEditExecuteRequest extends IDataSourceExecutionContext {
  sql: string;
}

export interface IDdlExecuteRequest extends IDataSourceExecutionContext {
  sql: string;
  tableName?: string;
}
