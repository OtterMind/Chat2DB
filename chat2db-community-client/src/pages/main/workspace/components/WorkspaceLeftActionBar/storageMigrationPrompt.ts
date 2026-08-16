export type StorageMigrationState =
  | 'NO_LEGACY_DATA'
  | 'AWAITING_MIGRATION'
  | 'PARTIALLY_MIGRATED'
  | 'READY_TO_DELETE'
  | 'CLEANED'
  | 'FAILED';

export interface StorageMigrationStatus {
  state: StorageMigrationState;
  legacyDataExists: boolean;
}

export const STORAGE_MIGRATION_STATUS_EVENT = 'chat2db:storage-migration-status';

export function needsStorageMigration(status: StorageMigrationStatus) {
  return (
    status.legacyDataExists &&
    (status.state === 'AWAITING_MIGRATION' || status.state === 'PARTIALLY_MIGRATED' || status.state === 'FAILED')
  );
}
