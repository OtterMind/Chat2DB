const UNKNOWN_LOAD_SQL_ERROR = 'Unknown Error';

/**
 * Resolves the message shown when the initial DDL load of a workspace tab fails.
 * Service rejections carry `errorMessage` for application errors and `message` for transport errors.
 */
export function resolveLoadSqlErrorMessage(error: unknown): string {
  if (typeof error === 'string') {
    return error || UNKNOWN_LOAD_SQL_ERROR;
  }
  if (error && typeof error === 'object') {
    const { errorMessage, message } = error as { errorMessage?: unknown; message?: unknown };
    if (typeof errorMessage === 'string' && errorMessage) {
      return errorMessage;
    }
    if (typeof message === 'string' && message) {
      return message;
    }
  }
  return UNKNOWN_LOAD_SQL_ERROR;
}

/**
 * Reports a failed initial DDL load into the editor error banner.
 * Keeps the rejection silent when the editor is already unmounted.
 */
export function reportLoadSqlError(
  error: unknown,
  showErrorMessage?: (message: string) => void,
): string {
  const message = resolveLoadSqlErrorMessage(error);
  showErrorMessage?.(message);
  return message;
}
