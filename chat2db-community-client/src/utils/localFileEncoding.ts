export interface LocalFileReadResult {
  content: string;
  charset: string;
  bom: boolean;
  path?: string;
  size?: number;
}

export interface LocalFileEncodingMetadata {
  charset?: string;
  bom?: boolean;
}

export const LOCAL_FILE_CHARSETS = [
  'UTF-8',
  'GB18030',
  'GBK',
  'UTF-16LE',
  'UTF-16BE',
  'Big5',
  'Shift_JIS',
  'EUC-KR',
  'windows-1252',
  'ISO-8859-1',
] as const;

export function formatLocalFileEncoding(charset?: string, bom?: boolean) {
  if (!charset) {
    return '';
  }
  return bom ? `${charset} BOM` : charset;
}

export function normalizeLocalFileReadResult(result: LocalFileReadResult) {
  return {
    content: result.content,
    charset: result.charset,
    bom: result.bom,
  };
}
