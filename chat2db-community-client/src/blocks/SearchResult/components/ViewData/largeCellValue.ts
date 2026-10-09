import { ILargeCellChunk, LargeValueType } from '@/typings/database';

export type LargeCellRequestFormat = 'text' | 'hex' | 'base64';
export type LargeCellDownloadFormat = 'raw' | 'text' | 'hex';
export type LargeCellViewerMode = 'text' | 'hex' | 'image';

export interface LoadedLargeCellChunk extends Omit<ILargeCellChunk, 'value' | 'encoding'> {
  bytes: Uint8Array;
}

export const LARGE_CELL_VIEWER_MODE = {
  TEXT: 'text',
  HEX: 'hex',
  IMAGE: 'image',
} as const satisfies Record<string, LargeCellViewerMode>;

export const LARGE_VALUE_TYPE = {
  TEXT: 'TEXT',
  JSON: 'JSON',
  BINARY: 'BINARY',
  IMAGE: 'IMAGE',
  UNKNOWN: 'UNKNOWN',
} as const satisfies Record<LargeValueType, LargeValueType>;

// The cell value endpoint also accepts "text" and "hex"; the client always transfers base64 bytes and decodes them
// once, which is what keeps a value spanning many chunks byte exact.
export const LARGE_CELL_REQUEST_FORMAT = {
  BASE64: 'base64',
} as const;

export const LARGE_CELL_DOWNLOAD_FORMAT = {
  RAW: 'raw',
  TEXT: 'text',
  HEX: 'hex',
} as const;

export const LARGE_CELL_ERROR_CODE = {
  TOKEN_EXPIRED: 'largeCellValue.tokenExpired',
  TOKEN_REQUIRED: 'largeCellValue.tokenRequired',
  FULL_VALUE_UNSUPPORTED: 'largeCellValue.fullValueUnsupported',
  READ_FAILED: 'largeCellValue.readFailed',
  DOWNLOAD_FAILED: 'largeCellValue.downloadFailed',
  PARTIAL_PREVIEW_EDIT_REJECTED: 'largeCellValue.partialPreviewEditRejected',
  UNSUPPORTED_FORMAT: 'largeCellValue.unsupportedFormat',
  SNAPSHOT_EXPIRED: 'largeCellValue.snapshotExpired',
  SNAPSHOT_CELL_MISSING: 'largeCellValue.snapshotCellMissing',
  SNAPSHOT_READ_FAILED: 'largeCellValue.snapshotReadFailed',
  SNAPSHOT_WRITE_FAILED: 'largeCellValue.snapshotWriteFailed',
} as const;

export const LARGE_CELL_ERROR_MESSAGE = {
  TOKEN_EXPIRED: 'common.largeCellValue.error.tokenExpired',
  TOKEN_REQUIRED: 'common.largeCellValue.error.tokenRequired',
  LOAD_FAILED: 'common.largeCellValue.error.loadFailed',
  DOWNLOAD_FAILED: 'common.largeCellValue.error.downloadFailed',
  FULL_VALUE_UNSUPPORTED: 'common.largeCellValue.error.fullValueUnsupported',
  PARTIAL_PREVIEW_EDIT_REJECTED: 'common.largeCellValue.error.partialPreviewEditRejected',
  UNSUPPORTED_FORMAT: 'common.largeCellValue.error.unsupportedFormat',
  SNAPSHOT_EXPIRED: 'common.largeCellValue.error.snapshotExpired',
  SNAPSHOT_CELL_MISSING: 'common.largeCellValue.error.snapshotCellMissing',
  SNAPSHOT_READ_FAILED: 'common.largeCellValue.error.snapshotReadFailed',
  SNAPSHOT_WRITE_FAILED: 'common.largeCellValue.error.snapshotWriteFailed',
} as const;

export const LARGE_CELL_TEXT_EDITOR_LIMIT = 10 * 1024 * 1024;
export const LARGE_CELL_BINARY_EDITOR_LIMIT = 50 * 1024 * 1024;
export const LARGE_CELL_MAX_CHUNK_SIZE = 256 * 1024;

export function isBinaryDisplayMode(displayMode?: LargeValueType | string) {
  return displayMode === LARGE_VALUE_TYPE.BINARY || displayMode === LARGE_VALUE_TYPE.IMAGE;
}

export function isTextDownloadMode(displayMode?: LargeValueType | string) {
  return displayMode === LARGE_VALUE_TYPE.TEXT || displayMode === LARGE_VALUE_TYPE.JSON;
}

export function getLargeCellEditorLimit(displayMode?: LargeValueType | string) {
  return isBinaryDisplayMode(displayMode) ? LARGE_CELL_BINARY_EDITOR_LIMIT : LARGE_CELL_TEXT_EDITOR_LIMIT;
}

export function getLargeCellTransferFormat(): LargeCellRequestFormat {
  return LARGE_CELL_REQUEST_FORMAT.BASE64;
}

export function getLargeCellViewerLimit(viewerMode: LargeCellViewerMode, valueType?: LargeValueType | string) {
  return viewerMode === LARGE_CELL_VIEWER_MODE.TEXT
    ? getLargeCellEditorLimit(valueType)
    : LARGE_CELL_BINARY_EDITOR_LIMIT;
}

export function getLargeCellDownloadFormat(displayMode?: LargeValueType | string): LargeCellDownloadFormat {
  return isTextDownloadMode(displayMode) ? LARGE_CELL_DOWNLOAD_FORMAT.TEXT : LARGE_CELL_DOWNLOAD_FORMAT.RAW;
}

export function getLargeCellLoadedBytes(chunks: Array<Pick<ILargeCellChunk, 'offset' | 'nextOffset'>>) {
  return chunks.reduce((sum, chunk) => sum + Math.max(0, chunk.nextOffset - chunk.offset), 0);
}

export function decodeLargeCellChunk(chunk: ILargeCellChunk): LoadedLargeCellChunk {
  const binary = atob(chunk.value || '');
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return {
    offset: chunk.offset,
    nextOffset: chunk.nextOffset,
    eof: chunk.eof,
    sizeBytes: chunk.sizeBytes,
    sizeChars: chunk.sizeChars,
    contentType: chunk.contentType,
    displayMode: chunk.displayMode,
    bytes,
  };
}

export function getNextLargeCellChunkLimit(params: {
  loadedSize: number;
  editorLimit: number;
}) {
  const remaining = params.editorLimit - params.loadedSize;
  if (remaining < 3) {
    return 0;
  }
  const nextLimit = Math.min(LARGE_CELL_MAX_CHUNK_SIZE, remaining);
  return nextLimit - (nextLimit % 3);
}

export function canSubmitLargeCellEdit(params: {
  isLargeValue: boolean;
  viewerMode: LargeCellViewerMode;
  displayMode?: LargeValueType | string;
  eof?: boolean;
  loadedSize: number;
  editorLimit: number;
}) {
  if (!params.isLargeValue) {
    return true;
  }
  return (
    params.viewerMode === LARGE_CELL_VIEWER_MODE.TEXT &&
    !isBinaryDisplayMode(params.displayMode) &&
    !!params.eof &&
    params.loadedSize <= params.editorLimit
  );
}

export function getLargeCellImagePreviewBlob(params: {
  viewerMode: LargeCellViewerMode;
  chunks: LoadedLargeCellChunk[];
  eof?: boolean;
  contentType?: string;
}) {
  if (params.viewerMode !== LARGE_CELL_VIEWER_MODE.IMAGE || !params.eof || !params.chunks.length) {
    return null;
  }
  const mimeType = params.contentType?.startsWith('image/') && params.contentType !== 'image/*'
    ? params.contentType
    : 'image/png';
  return new Blob(params.chunks.map((chunk) => chunk.bytes as BlobPart), { type: mimeType });
}

const bytesToHex = (bytes: Uint8Array) => {
  const blockSize = 0x8000;
  let hex = '';
  for (let offset = 0; offset < bytes.length; offset += blockSize) {
    const block = bytes.subarray(offset, offset + blockSize);
    const pairs = Array.from(block, (byte) => byte.toString(16).padStart(2, '0'));
    hex += pairs.join('');
  }
  return hex.toUpperCase();
};

export function getLargeCellViewerValue(params: {
  viewerMode: LargeCellViewerMode;
  chunks: LoadedLargeCellChunk[];
  preview?: string | null;
}) {
  if (!params.chunks.length) {
    return params.preview || '';
  }
  if (params.viewerMode === LARGE_CELL_VIEWER_MODE.IMAGE) {
    return '';
  }
  if (params.viewerMode === LARGE_CELL_VIEWER_MODE.HEX) {
    return params.chunks.map((chunk) => bytesToHex(chunk.bytes)).join('');
  }
  const decoder = new TextDecoder();
  return params.chunks
    .map((chunk, index) => decoder.decode(chunk.bytes, { stream: index < params.chunks.length - 1 }))
    .join('');
}

export function isLargeCellTokenExpiredError(error: any) {
  const errorCode = error?.errorCode;
  // The handle can be gone in three ways: the handle itself expired, the snapshot behind it expired, or the cell is no
  // longer part of it. All three mean "load again from the database".
  return (
    errorCode === LARGE_CELL_ERROR_CODE.TOKEN_EXPIRED ||
    errorCode === LARGE_CELL_ERROR_CODE.SNAPSHOT_EXPIRED ||
    errorCode === LARGE_CELL_ERROR_CODE.SNAPSHOT_CELL_MISSING
  );
}
