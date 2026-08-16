export function getDeleteTableErrorMessage(error: unknown, fallback: string): string {
  if (typeof error === 'string') {
    return error || fallback;
  }
  if (!error || typeof error !== 'object') {
    return fallback;
  }

  const value = error as { errorMessage?: unknown; message?: unknown };
  if (typeof value.errorMessage === 'string' && value.errorMessage) {
    return value.errorMessage;
  }
  if (typeof value.message === 'string' && value.message) {
    return value.message;
  }
  return fallback;
}
