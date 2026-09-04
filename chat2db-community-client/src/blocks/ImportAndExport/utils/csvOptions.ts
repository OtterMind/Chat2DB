import { ICsvOptions } from '@/service/sql';
import { ImportExportFileType } from '@/constants/importExport';

export const DEFAULT_CSV_OPTIONS: ICsvOptions = {
  encoding: 'UTF-8',
  delimiter: ',',
  quote: '"',
  escape: '"',
  newline: 'LF',
  hasHeader: true,
  emptyAsNull: true,
};

const SUPPORTED_ENCODINGS = ['AUTO', 'UTF-8', 'UTF-16LE', 'UTF-16BE', 'GB18030', 'ISO-8859-1', 'WINDOWS-1252', 'SHIFT_JIS', 'BIG5'];
const SUPPORTED_DELIMITERS = [',', ';', '\t', '|'];
const SUPPORTED_NEWLINES = ['LF', 'CRLF', 'CR'];

export function validateCsvOptions(options: ICsvOptions): ICsvOptions {
  const normalized: ICsvOptions = {
    encoding: (options.encoding || DEFAULT_CSV_OPTIONS.encoding).trim().toUpperCase(),
    delimiter: options.delimiter || DEFAULT_CSV_OPTIONS.delimiter,
    quote: options.quote || DEFAULT_CSV_OPTIONS.quote,
    escape: options.escape || DEFAULT_CSV_OPTIONS.escape,
    newline: (options.newline || DEFAULT_CSV_OPTIONS.newline).trim().toUpperCase(),
    hasHeader: options.hasHeader ?? DEFAULT_CSV_OPTIONS.hasHeader,
    emptyAsNull: options.emptyAsNull ?? DEFAULT_CSV_OPTIONS.emptyAsNull,
  };
  if (
    !SUPPORTED_ENCODINGS.includes(normalized.encoding) ||
    !SUPPORTED_DELIMITERS.includes(normalized.delimiter) ||
    !SUPPORTED_NEWLINES.includes(normalized.newline) ||
    normalized.quote.length !== 1 ||
    normalized.escape.length !== 1 ||
    normalized.quote === '\n' ||
    normalized.quote === '\r' ||
    normalized.escape === '\n' ||
    normalized.escape === '\r' ||
    normalized.delimiter === normalized.quote ||
    normalized.delimiter === normalized.escape
  ) {
    throw new Error('Unsupported CSV option combination');
  }
  return normalized;
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
