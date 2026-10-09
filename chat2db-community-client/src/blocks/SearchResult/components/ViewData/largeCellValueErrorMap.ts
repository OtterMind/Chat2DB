import { LARGE_CELL_ERROR_MESSAGE } from './largeCellValue';

// The backend returns its i18n key as both errorCode and errorMessage (for example
// "<backend key> : no message."), so this map owns the backend key to display key translation.
type LargeCellMessageKey = (typeof LARGE_CELL_ERROR_MESSAGE)[keyof typeof LARGE_CELL_ERROR_MESSAGE];

export const LARGE_CELL_MESSAGE_BY_CODE: Record<string, LargeCellMessageKey> = {
  'largeCellValue.tokenExpired': LARGE_CELL_ERROR_MESSAGE.TOKEN_EXPIRED,
  'largeCellValue.tokenRequired': LARGE_CELL_ERROR_MESSAGE.TOKEN_REQUIRED,
  'largeCellValue.fullValueUnsupported': LARGE_CELL_ERROR_MESSAGE.FULL_VALUE_UNSUPPORTED,
  'largeCellValue.readFailed': LARGE_CELL_ERROR_MESSAGE.LOAD_FAILED,
  'largeCellValue.downloadFailed': LARGE_CELL_ERROR_MESSAGE.DOWNLOAD_FAILED,
  'largeCellValue.partialPreviewEditRejected': LARGE_CELL_ERROR_MESSAGE.PARTIAL_PREVIEW_EDIT_REJECTED,
  'largeCellValue.unsupportedFormat': LARGE_CELL_ERROR_MESSAGE.UNSUPPORTED_FORMAT,
  'largeCellValue.snapshotExpired': LARGE_CELL_ERROR_MESSAGE.SNAPSHOT_EXPIRED,
  'largeCellValue.snapshotCellMissing': LARGE_CELL_ERROR_MESSAGE.SNAPSHOT_CELL_MISSING,
  'largeCellValue.snapshotReadFailed': LARGE_CELL_ERROR_MESSAGE.SNAPSHOT_READ_FAILED,
  'largeCellValue.snapshotWriteFailed': LARGE_CELL_ERROR_MESSAGE.SNAPSHOT_WRITE_FAILED,
};

export function getLargeCellMessageKey(
  message?: string | null,
): LargeCellMessageKey | undefined {
  if (!message) {
    return undefined;
  }
  const code = String(message).split(':')[0].trim();
  return LARGE_CELL_MESSAGE_BY_CODE[code];
}
