export function hasUnsavedSavedConsoleChanges(
  value: string,
  hasSavedSqlRecord: boolean,
  lastSyncValue: string,
) {
  if (!hasSavedSqlRecord) {
    return Boolean(value.trim());
  }
  return value !== lastSyncValue;
}
