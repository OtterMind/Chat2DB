import { TIP_TYPE } from '@/components/SQLEditor';
import * as monaco from 'monaco-editor';
export interface ISimpleDatabaseVO {
  datasourceName: string;
  databaseName: string;
  insertText: string;
}

export interface ISimpleSchemaVO {
  datasourceName: string;
  databaseName: string;
  schemaName: string;
  insertText: string;
}

export interface ISimpleTableVO {
  datasourceName: string;
  databaseName: string;
  schemaName: string;
  tableName: string;
  tableAlias: string;
  comment: string;
  insertText: string;
}

export interface ISimpleColumnVO {
  datasourceName: string;
  databaseName: string;
  schemaName: string;
  tableName: string;
  columnName: string;
  dataType: string;
  comment: string;
  insertText: string;
}

export interface ISimpleViewVO {
  datasourceName: string;
  databaseName: string;
  schemaName: string;
  viewName: string;
  insertText: string;
}

export interface ISimpleFunctionVO {
  datasourceName: string;
  databaseName: string;
  schemaName: string;
  functionName: string;
  returnType: string;
  parameters: Array<{ parameterName: string; parameterType: string }>;
  insertText: string;
}

export interface ISimpleProcedureVO {
  datasourceName: string;
  databaseName: string;
  schemaName: string;
  procedureName: string;
  returnType: string;
  parameters: Array<{ parameterName: string; parameterType: string }>;
  insertText: string;
}

export interface SqlStatement {
  sql: string;
  sqlStartRowNum: number;
  sqlStartColNum: number;
  sqlEndRowNum: number;
  sqlEndColNum: number;
  /** The type of this SQL */
  type: SqlTypeEnum;
  /** Is this SQL valid? */
  statementType: StatementValidTypeEnum;
  /** Single line comment content before the sql statement */
  comment: string;
  /** All identifiers involved in this SQL */
  identifiers: SimpleIdentifier[];
  tableColumns: Array<{
    simpleColumns: Array<ISimpleColumnVO>;
    simpleTable: ISimpleTableVO;
  }>;
  insertValueMappings?: SimpleInsertValueMapping[];
}

export interface MarkMessage {
  endLineNum: number;
  startLineNum: number;
  startColNum: number;
  endColNum: number;
  message: string;
  /** [error, warning, ...] */
  type: string;
}

export interface SimpleIdentifier {
  name: string;
  alias: string;
  type: string;
  identifierDatabase: string;
  identifierSchema: string;
  identifierTable: string;
  identifierStartRowNum: number;
  identifierStartColNum: number;
  identifierEndColNum: number;
  identifierEndRowNum: number;
  aliasStartRowNum: number;
  aliasStartColNum: number;
  aliasEndColNum: number;
  aliasEndRowNum: number;
}

export interface SimpleInsertValueMapping {
  columnStartRowNum?: number | null;
  columnStartColNum?: number | null;
  columnEndRowNum?: number | null;
  columnEndColNum?: number | null;
  valueStartRowNum?: number | null;
  valueStartColNum?: number | null;
  valueEndRowNum?: number | null;
  valueEndColNum?: number | null;
  rowStartRowNum?: number | null;
  rowStartColNum?: number | null;
  rowEndRowNum?: number | null;
  rowEndColNum?: number | null;
  rowIndex: number;
  columnIndex: number;
  mappingStatus: InsertValueMappingStatusEnum;
}

export enum InsertValueMappingStatusEnum {
  MATCHED = 'MATCHED',
  UNMAPPED_COLUMN = 'UNMAPPED_COLUMN',
  UNMAPPED_VALUE = 'UNMAPPED_VALUE',
}

export interface IHoverInfo {
  /** The database where it is located */
  databaseName: string;
  /** The schema where */
  schemaName: string;

  /** The table where */
  tableName: string;
  /** The name of the data source where it is located */
  datasourceName: string;
  /** Associated view name */
  viewName: string;
  /** Associated trigger name */
  triggerName: string;
  /** Associated ddl */
  ddl: string;
  /** Associated comments */
  comment: string;
  /** Associated column name */
  columnName: string;
  /** Associated column types */
  dataType: string;
}

export interface ITipItemVO {
  /** Content of prompt */
  value: string;
  /** Insert content */
  insertText?: string;
  /** Insert text or code block */
  insertTextRules?: monaco.languages.CompletionItemInsertTextRule;
  /** Candidate your own replacement starting point */
  replaceStart?: number;
  /** Candidate your own replacement endpoint */
  replaceEnd?: number;
  /** Type of prompt */
  type: TIP_TYPE;
  /** The field type when type is Column */
  dataType: string;
  /** Comments on prompt content */
  comment: string;
  /** Data source name */
  datasourceName: string;
  /** Database name */
  databaseName?: string;
  /** schema name */
  schemaName?: string;
  /** Table name */
  tableName?: string;
  /** table alias */
  tableAlias?: string;
  /** Column name */
  columnName?: string;
  command?: monaco.languages.Command;
  sortText?: string;
  detail?: string;
  description?: string;
  snippetSlots?: SqlCompletionSnippetSlotType[];
}

export type SqlCompletionStatus = 'SUCCESS' | 'EMPTY' | 'UNSUPPORTED' | 'REJECTED';

export type SqlCompletionKeywordCase = 'UPPER' | 'LOWER';

export type SqlCompletionInsertType = 'PLAIN_TEXT' | 'SNIPPET';

export type SqlCompletionSnippetSlotType = 'SELECT_FUNCTION' | 'INSERT_COLUMN_LIST' | 'CALL_PROCEDURE';

export interface ISqlCompletionActiveSnippetSlot {
  type?: SqlCompletionSnippetSlotType;
  replaceStart?: number;
  replaceEnd?: number;
}

export interface ISqlCompletionCandidate {
  id?: string;
  label?: string;
  type?: string;
  insertText?: string;
  insertType?: SqlCompletionInsertType;
  replaceStart?: number;
  replaceEnd?: number;
  detail?: string;
  description?: string;
  dataType?: string;
  objectType?: string;
  comment?: string;
  datasourceName?: string;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
  tableAlias?: string;
  columnName?: string;
  objectName?: string;
  sortRank?: number;
  sortText?: string;
  snippetSlots?: SqlCompletionSnippetSlotType[];
}

export interface ISqlEditorHintRangeVO {
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
}

export interface ISqlEditorHintItemVO {
  rowIndex: number;
  columnIndex: number;
  fieldName?: string;
  fieldType?: string;
  defaultValue?: string;
  label?: string;
  range?: ISqlEditorHintRangeVO;
  active?: boolean;
}

export interface ISqlEditorHintVO {
  type: 'INSERT_VALUE' | 'ROUTINE_PARAMETER' | string;
  statementRange?: ISqlEditorHintRangeVO;
  rowRange?: ISqlEditorHintRangeVO;
  valueRange?: ISqlEditorHintRangeVO;
  items?: ISqlEditorHintItemVO[];
}

export interface ISqlCompletionResult {
  status: SqlCompletionStatus;
  replaceStart: number;
  replaceEnd: number;
  candidates: ISqlCompletionCandidate[];
  editorHints?: ISqlEditorHintVO[];
  reasonCode?: string;
}

export enum StatementValidTypeEnum {
  VALID = 'VALID',
  INVALID = 'INVALID',
}

// export enum TipTypeEnum {
//   DATABASE = 'DATABASE',
//   SCHEMA = 'SCHEMA',
//   TABLE = 'TABLE',
//   VIEW = 'VIEW',
//   COLUMN = 'COLUMN',
//   PARAMETER = 'PARAMETER',
//   JOIN_CLAUSE = 'JOIN_CLAUSE',
// }

export enum SqlTypeEnum {
  SELECT = 'SELECT',
  INSERT = 'INSERT',
  UPDATE = 'UPDATE',
  DELETE = 'DELETE',
  ALTER = 'ALTER',
  CREATE = 'CREATE',
  DROP = 'DROP',
  DROP_DATABASE = 'DROP_DATABASE',
  DROP_TABLE = 'DROP_TABLE',
  DROP_VIEW = 'DROP_VIEW',
  DROP_FUNCTION = 'DROP_FUNCTION',
  DROP_PROCEDURE = 'DROP_PROCEDURE',
  DROP_USER = 'DROP_USER',
  DROP_ROLE = 'DROP_ROLE',
  DROP_EVENT = 'DROP_EVENT',
  DROP_INDEX = 'DROP_INDEX',
  DROP_TRIGGER = 'DROP_TRIGGER',
  DROP_CONSTRAINT = 'DROP_CONSTRAINT',
  DROP_SEQUENCE = 'DROP_SEQUENCE',
  DROP_COLUMN = 'DROP_COLUMN',
  DROP_SCHEMA = 'DROP_SCHEMA',
  CREATE_TABLE = 'CREATE_TABLE',
  CREATE_VIEW = 'CREATE_VIEW',
  CREATE_DATABASE = 'CREATE_DATABASE',
  CREATE_SCHEMA = 'CREATE_SCHEMA',
  CREATE_FUNCTION = 'CREATE_FUNCTION',
  CREATE_PROCEDURE = 'CREATE_PROCEDURE',
  CREATE_USER = 'CREATE_USER',
  CREATE_EVENT = 'CREATE_EVENT',
  CREATE_INDEX = 'CREATE_INDEX',
  CREATE_TRIGGER = 'CREATE_TRIGGER',
  CREATE_ROLE = 'CREATE_ROLE',
  CREATE_CONSTRAINT = 'CREATE_CONSTRAINT',
  CREATE_SEQUENCE = 'CREATE_SEQUENCE',
  CREATE_COLUMN = 'CREATE_COLUMN',
  USE_DATABASE = 'USE_DATABASE',
  SET_SCHEMA = 'SET_SCHEMA',
  DROP_ROUTINE = 'DROP_ROUTINE',
  OTHER = 'OTHER',
}
