import type { ICsvOptions } from '@/typings/importExport';
import { ImportExportFileType, ImportPreviewErrorCode } from '@/constants/importExport';

export const DEFAULT_CSV_OPTIONS: ICsvOptions = {
  encoding: 'AUTO',
  delimiter: ',',
  quote: '"',
  escape: '"',
  newline: 'LF',
  hasHeader: true,
  emptyAsNull: true,
  headerRow: 1,
  dataStartRow: 2,
  dataEndRow: undefined,
  dateOrder: 'YMD',
  dateTimeOrder: 'DATE_TIME',
  dateDelimiter: '-',
  yearDelimiter: '-',
  timeDelimiter: ':',
  decimalSymbol: '.',
};

export class CsvOptionsValidationError extends Error {
  readonly errorCode = ImportPreviewErrorCode.INVALID_CSV_OPTIONS;

  constructor() {
    super();
    this.name = 'CsvOptionsValidationError';
  }
}

const SUPPORTED_NEWLINES = ['LF', 'CRLF', 'CR'];
const SUPPORTED_DATE_ORDERS = ['YMD', 'YDM', 'MDY', 'MYD', 'DMY', 'DYM'];
const SUPPORTED_DATE_TIME_ORDERS = [
  'DATE_TIME',
  'TIME_DATE',
  'DATE_TIME_TIMEZONE',
  'TIME_DATE_TIMEZONE',
  'TIME_TIMEZONE_DATE',
];

export function validateCsvOptions(options: ICsvOptions): ICsvOptions {
  const normalized: ICsvOptions = {
    encoding: (options.encoding || DEFAULT_CSV_OPTIONS.encoding).trim().toUpperCase(),
    delimiter: options.delimiter || DEFAULT_CSV_OPTIONS.delimiter,
    quote: options.quote || DEFAULT_CSV_OPTIONS.quote,
    escape: options.escape || DEFAULT_CSV_OPTIONS.escape,
    newline: (options.newline || DEFAULT_CSV_OPTIONS.newline).trim().toUpperCase(),
    hasHeader: options.hasHeader ?? DEFAULT_CSV_OPTIONS.hasHeader,
    emptyAsNull: options.emptyAsNull ?? DEFAULT_CSV_OPTIONS.emptyAsNull,
    headerRow: options.headerRow ?? DEFAULT_CSV_OPTIONS.headerRow,
    dataStartRow: options.dataStartRow ?? (options.hasHeader ? (options.headerRow || 1) + 1 : 1),
    dataEndRow: options.dataEndRow,
    dateOrder: options.dateOrder || DEFAULT_CSV_OPTIONS.dateOrder,
    dateTimeOrder: options.dateTimeOrder || DEFAULT_CSV_OPTIONS.dateTimeOrder,
    dateDelimiter: options.dateDelimiter || DEFAULT_CSV_OPTIONS.dateDelimiter,
    yearDelimiter: options.yearDelimiter || options.dateDelimiter || DEFAULT_CSV_OPTIONS.yearDelimiter,
    timeDelimiter: options.timeDelimiter || DEFAULT_CSV_OPTIONS.timeDelimiter,
    decimalSymbol: options.decimalSymbol || DEFAULT_CSV_OPTIONS.decimalSymbol,
  };
  if (
    !normalized.encoding ||
    normalized.delimiter.length !== 1 ||
    !SUPPORTED_NEWLINES.includes(normalized.newline) ||
    normalized.quote.length !== 1 ||
    normalized.escape.length !== 1 ||
    normalized.quote === '\n' ||
    normalized.quote === '\r' ||
    normalized.escape === '\n' ||
    normalized.escape === '\r' ||
    normalized.delimiter === normalized.quote ||
    normalized.delimiter === normalized.escape ||
    !Number.isInteger(normalized.headerRow) ||
    normalized.headerRow < 1 ||
    !Number.isInteger(normalized.dataStartRow) ||
    normalized.dataStartRow < 1 ||
    (normalized.dataEndRow !== undefined &&
      (!Number.isInteger(normalized.dataEndRow) || normalized.dataEndRow < normalized.dataStartRow)) ||
    (normalized.hasHeader && normalized.headerRow >= normalized.dataStartRow) ||
    !SUPPORTED_DATE_ORDERS.includes(normalized.dateOrder) ||
    !SUPPORTED_DATE_TIME_ORDERS.includes(normalized.dateTimeOrder) ||
    normalized.dateDelimiter.length !== 1 ||
    normalized.yearDelimiter.length !== 1 ||
    normalized.timeDelimiter.length !== 1 ||
    !['.', ','].includes(normalized.decimalSymbol)
  ) {
    throw new CsvOptionsValidationError();
  }
  return normalized;
}

export function getCsvDateTimeExamples(options: ICsvOptions): string[] {
  const values = [
    { year: '23', month: '8', day: '24' },
    { year: '2023', month: '8', day: '24' },
    { year: '23', month: 'Aug', day: '24' },
    { year: '23', month: 'August', day: '24' },
  ];
  return values.map(({ year, month, day }) => {
    const parts = { Y: year, M: month, D: day };
    const order = options.dateOrder.split('') as Array<keyof typeof parts>;
    const separators = [0, 1].map((index) => {
      const nextToYear = order[index] === 'Y' || order[index + 1] === 'Y';
      return nextToYear ? options.yearDelimiter : options.dateDelimiter;
    });
    const date = `${parts[order[0]]}${separators[0]}${parts[order[1]]}${separators[1]}${parts[order[2]]}`;
    const time = `15${options.timeDelimiter}30${options.timeDelimiter}38`;
    switch (options.dateTimeOrder) {
      case 'TIME_DATE':
        return `${time} ${date}`;
      case 'DATE_TIME_TIMEZONE':
        return `${date} ${time} +08:00`;
      case 'TIME_DATE_TIMEZONE':
        return `${time} ${date} +08:00`;
      case 'TIME_TIMEZONE_DATE':
        return `${time} +08:00 ${date}`;
      default:
        return `${date} ${time}`;
    }
  });
}

export function buildCsvOptionsForTaskSubmit(isCsv: boolean, options: ICsvOptions): ICsvOptions | undefined {
  return isCsv ? validateCsvOptions(options) : undefined;
}

export function csvOptionsToPreviewParam(isCsv: boolean, options: ICsvOptions): string | undefined {
  const validated = buildCsvOptionsForTaskSubmit(isCsv, options);
  return validated ? JSON.stringify(validated) : undefined;
}

export function inferImportFileFormat(filePath: string): ImportExportFileType {
  const lower = filePath.toLowerCase();
  if (lower.endsWith('.xlsx')) {
    return ImportExportFileType.XLSX;
  }
  if (lower.endsWith('.xls')) {
    return ImportExportFileType.XLS;
  }
  if (lower.endsWith('.json')) {
    return ImportExportFileType.JSON;
  }
  if (lower.endsWith('.sql')) {
    return ImportExportFileType.SQL;
  }
  return ImportExportFileType.CSV;
}

export function supportsCsvMappingPreview(format?: ImportExportFileType): boolean {
  return format === ImportExportFileType.CSV;
}
