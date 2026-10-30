import type {
  ICsvOptions,
  IExcelOptions,
  IJsonOptions,
  ISqlImportOptions,
  IImportValueOptions,
  ISourceRowOptions,
} from '@/typings/importExport';

interface ImportPreviewKeyParams {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  tableName: string;
  fileId?: string;
  csvOptions?: ICsvOptions;
  excelOptions?: IExcelOptions;
  jsonOptions?: IJsonOptions;
}

const previewOptions = <T extends { emptyAsNull: boolean }>(options?: T) => {
  if (!options) return undefined;
  const readingOptions: Partial<T> = { ...options };
  delete readingOptions.emptyAsNull;
  return readingOptions;
};

/**
 * The preview displays source text (both null and empty cells display as an empty string).
 * Changing the write-time empty-cell policy must preserve the loaded preview and edited mapping;
 * all file, target and reading options still invalidate it.
 */
export const buildImportPreviewKey = ({
  csvOptions, excelOptions, jsonOptions, ...source
}: ImportPreviewKeyParams): string => JSON.stringify({
  ...source,
  csvOptions: previewOptions(csvOptions),
  excelOptions: previewOptions(excelOptions),
  jsonOptions: previewOptions(jsonOptions),
});

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
