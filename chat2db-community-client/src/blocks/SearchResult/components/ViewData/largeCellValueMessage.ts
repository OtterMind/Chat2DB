import i18n from '@/i18n';
import { LARGE_CELL_ERROR_MESSAGE, isLargeCellTokenExpiredError } from './largeCellValue';
import { getLargeCellMessageKey } from './largeCellValueErrorMap';

export function getLargeCellDisplayMessage(message?: string | null) {
  if (!message) {
    return '';
  }
  const messageKey = getLargeCellMessageKey(message);
  return messageKey ? i18n(messageKey) : message;
}

export function getLargeCellErrorMessage(
  error: any,
  fallback: (typeof LARGE_CELL_ERROR_MESSAGE)[keyof typeof LARGE_CELL_ERROR_MESSAGE],
) {
  if (isLargeCellTokenExpiredError(error)) {
    return i18n(LARGE_CELL_ERROR_MESSAGE.TOKEN_EXPIRED);
  }
  return (
    getLargeCellDisplayMessage(error?.errorCode) ||
    getLargeCellDisplayMessage(error?.errorMessage || error?.message) ||
    i18n(fallback)
  );
}
