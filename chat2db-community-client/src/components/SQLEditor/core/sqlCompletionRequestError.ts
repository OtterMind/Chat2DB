const EXPECTED_PERMISSION_ERROR_CODES = new Set([
  'NO_DATA_ACCESS_PERMISSION',
  'NO_DATA_ACCESS_PERMISSION_DETAIL',
  'common.permissionDenied',
]);

export function isExpectedSqlCompletionPermissionError(error: unknown): boolean {
  if (!error || typeof error !== 'object' || !('errorCode' in error)) {
    return false;
  }
  return EXPECTED_PERMISSION_ERROR_CODES.has(String((error as { errorCode?: unknown }).errorCode));
}
