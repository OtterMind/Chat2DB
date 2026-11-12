export interface IDataSourceExecutionContext {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string | null;
}

/** The kind of value typed for one SQL parameter; each maps to one JDBC bind call. */
export type SqlParameterType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'NULL';

/**
 * A typed value for one SQL parameter placeholder. `NULL` carries a `null`
 * value; an empty `STRING` binds the empty string. Values are bound by the JDBC
 * driver and never written into the SQL text.
 */
export interface ISqlParameterValue {
  type: SqlParameterType;
  value: string | null;
}

/** Values for the placeholders of one SQL text: by name for `:name`, in order for `?`. */
export interface ISqlParameterValues {
  parameters?: Record<string, ISqlParameterValue>;
  positionalParameters?: ISqlParameterValue[];
}

export interface ISqlEditorExecuteRequest extends IDataSourceExecutionContext, ISqlParameterValues {
  sql: string;
  consoleId?: number;
  applyId?: number;
  pageNo?: number;
  pageSize?: number;
  single?: boolean;
  resultSetId?: number;
  errorContinue?: boolean;
  explain?: boolean;
  /** The editor's SQL parameters switch; it never decides whether supplied values are bound. */
  sqlParameters?: boolean;
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
  errorContinue?: boolean;
}
