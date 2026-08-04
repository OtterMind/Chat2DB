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

export function normalizeLocalFileReadResult(result: LocalFileReadResult) {
  return {
    content: result.content,
    charset: result.charset,
    bom: result.bom,
  };
}
