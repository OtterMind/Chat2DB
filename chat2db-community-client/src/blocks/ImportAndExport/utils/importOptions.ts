import type {
  IExcelOptions,
  IJsonOptions,
  ISqlImportOptions,
  IImportValueOptions,
  ISourceRowOptions,
} from '@/typings/importExport';

const DEFAULT_VALUE_OPTIONS: IImportValueOptions = {
  dateOrder: 'YMD',
  dateTimeOrder: 'DATE_TIME',
  dateDelimiter: '-',
  yearDelimiter: '-',
  timeDelimiter: ':',
  decimalSymbol: '.',
};
const DEFAULT_SOURCE_ROWS: ISourceRowOptions = { hasHeader: true, headerRow: 1, dataStartRow: 2 };

export const DEFAULT_EXCEL_OPTIONS: IExcelOptions = {
  ...DEFAULT_VALUE_OPTIONS,
  ...DEFAULT_SOURCE_ROWS,
  sheetIndex: 0,
  columnRange: '',
  emptyAsNull: true,
};
export const DEFAULT_JSON_OPTIONS: IJsonOptions = {
  ...DEFAULT_VALUE_OPTIONS,
  encoding: 'UTF-8',
  structure: 'ARRAY',
  dataPath: '$',
  emptyAsNull: false,
};
export const DEFAULT_SQL_IMPORT_OPTIONS: ISqlImportOptions = { encoding: 'AUTO' };
