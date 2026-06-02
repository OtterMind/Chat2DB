export interface ICompareOption {
  compareColumn?: boolean;
  compareIndex?: boolean;
  compareForeignKey?: boolean;
  compareTableOption?: boolean;
  caseSensitive?: boolean;
  excludeDeprecated?: boolean;
  ignoreCharsetAlias?: boolean;
  ignoreIntegerDisplayWidth?: boolean;
  ignoreAutoIncrement?: boolean;
}

export interface IDiffSummary {
  totalTables: number;
  tablesOnlyInSource: number;
  tablesOnlyInTarget: number;
  modifiedTables: number;
  unchangedTables: number;
  excludedDeprecatedTables: number;
}

export interface IFieldDiff {
  fieldName: string;
  sourceValue?: string;
  targetValue?: string;
}

/** 列对比差异项 */
export interface IColumnDiff {
  changeType: 'ADD' | 'MODIFY' | 'DELETE';
  sourceColumn?: ITableColumn;
  targetColumn?: ITableColumn;
  changedFields?: IFieldDiff[];
}

/** 索引对比差异项 */
export interface IIndexDiff {
  changeType: 'ADD' | 'MODIFY' | 'DELETE';
  sourceIndex?: ITableIndex;
  targetIndex?: ITableIndex;
  changedFields?: IFieldDiff[];
}

/** 外键对比差异项 */
export interface IForeignKeyDiff {
  changeType: 'ADD' | 'MODIFY' | 'DELETE';
  sourceForeignKey?: ITableForeignKey;
  targetForeignKey?: ITableForeignKey;
  changedFields?: IFieldDiff[];
}

/** 表列信息 */
export interface ITableColumn {
  name?: string;
  dataType?: string;
  columnType?: string;
  columnSize?: number;
  decimalDigits?: number;
  nullable?: number;
  defaultValue?: string;
  comment?: string;
  autoIncrement?: boolean;
  charSetName?: string;
  collationName?: string;
  primaryKey?: boolean;
  primaryKeyOrder?: number;
  ordinalPosition?: number;
  editStatus?: string;
  oldName?: string;
}

/** 表索引信息 */
export interface ITableIndex {
  name?: string;
  type?: string;
  unique?: boolean;
  method?: string;
  comment?: string;
  columnList?: Array<{ columnName?: string; ascOrDesc?: string }>;
  editStatus?: string;
  oldName?: string;
}

/** 表外键信息 */
export interface ITableForeignKey {
  name?: string;
  column?: string;
  referencedTable?: string;
  referencedColumn?: string;
  updateRule?: number;
  deleteRule?: number;
  comment?: string;
  editStatus?: string;
  oldName?: string;
}

export interface ITableDiff {
  tableName: string;
  diffType: 'ADDED' | 'REMOVED' | 'MODIFIED' | 'UNCHANGED';
  sourceTable?: any;
  targetTable?: any;
  columnDiffs?: IColumnDiff[];
  indexDiffs?: IIndexDiff[];
  foreignKeyDiffs?: IForeignKeyDiff[];
  tableOptionDiffs?: IFieldDiff[];
  ddlStatements?: string[];
  ddlStatement?: string;
}

export interface ISchemaDiffResult {
  sourceKey: string;
  targetKey: string;
  summary: IDiffSummary;
  tableDiffs: ITableDiff[];
  warnings?: string[];
}

export interface IMigrationStatementResult {
  sequence: number;
  sql: string;
  success: boolean;
  errorMessage?: string;
  duration?: number;
}

export interface IMigrateResult {
  success: boolean;
  statementResults: IMigrationStatementResult[];
  totalStatements: number;
  successCount: number;
  failCount: number;
}

export interface ISchemaCompareParams {
  sourceDataSourceId: number;
  sourceDatabaseName: string;
  sourceSchemaName?: string;
  targetDataSourceId: number;
  targetDatabaseName: string;
  targetSchemaName?: string;
  tableNames?: string[];
  compareOption?: ICompareOption;
}

export interface ISchemaMigrateParams {
  targetDataSourceId: number;
  targetDatabaseName: string;
  targetSchemaName?: string;
  ddlStatements: string[];
  executeInTransaction?: boolean;
  continueOnError?: boolean;
}
