import { EditColumnOperationType, NullableType } from '@/constants';

// Basic data of the table when editing the table
export interface IBaseInfo {
  name: string;
  comment?: string | null;
  charset: string | null; // character set
  engine: string | null; // engine
  incrementValue: string | null; // self-added value
}

export interface IColumnItemNew {
  editStatus: EditColumnOperationType | null; // Operation type

  key?: string;
  oldName: string | null; // Old listing
  name: string | null; // List

  databaseName: string | null; // Database name
  schemaName: string | null; // Schema name
  tableName: string | null; // table name

  columnType: string | null; // Column type, such as varchar(100), double(10,6)
  dataType: number | null; // data type
  defaultValue: string | null; // Default value
  autoIncrement: boolean | null; // Whether to increment automatically
  comment: string | null; // Comment
  primaryKey: boolean | null; // Is it a primary key?
  primaryKeyOrder: number | null; // primary key order
  typeName: string | null; // Type name
  columnSize: number | null; // column length
  bufferLength: number | null; // buffer length
  decimalDigits: string | null; // Decimal places
  numPrecRadix: number | null; // Numerical precision
  sqlDataType: string | null; // sql data type
  sqlDatetimeSub: string | null; // sql datetime subtype
  charOctetLength: string | null; // Maximum length of string
  ordinalPosition: number | null; // location
  nullable: NullableType | null; //Is it empty
  generatedColumn: boolean | null; // Whether to generate columns
  generationExpression: string | null; // Generated column expression (MYSQL-OBJ-002)
  generatedColumnType: string | null; // VIRTUAL | STORED
  onUpdateCurrentTimestamp?: boolean | null; // ON UPDATE CURRENT_TIMESTAMP
  visible?: boolean | null; // Column visibility (MySQL 8.0.23+)

  charSetName: string | null; // Character set name
  collationName: string | null; // collation name
  value: string | null; // value
}

//
export interface IIndexIncludeColumnItem {
  key?: string; // The unique identifier added by the front end
  ascOrDesc: string | null; // Ascending or descending order
  cardinality: number | null; // Cardinality
  collation: string | null; // Sorting rules
  columnName: string | null; // List
  comment: string | null; // Comment
  databaseName: string | null; // Database name
  filterCondition: string | null; // filter conditions
  indexName: string | null; // Index name
  indexQualifier: string | null; // index qualifier
  nonUnique: boolean | null; // Is it unique?
  ordinalPosition: number | null; // location
  schemaName: string | null; // Schema name
  tableName: string | null; // table name
  type: string | null; // Type
  pages: number | null; // Number of pages
}

// Data structure of index when editing table
export interface IIndexItem {
  key?: string;
  name: string | null;
  comment?: string | null;
  type: any | null;
  method?: string | null;
  visible?: boolean | null;
  columnList: IIndexIncludeColumnItem[];
  editStatus: EditColumnOperationType | null; // Operation type
}

// The overall data structure when editing the table
export interface IEditTableInfo extends IBaseInfo {
  columnList: IColumnItemNew[];
  indexList: IIndexItem[];
}
