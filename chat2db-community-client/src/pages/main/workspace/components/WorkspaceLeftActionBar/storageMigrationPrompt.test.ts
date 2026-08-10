import assert from 'node:assert/strict';
import { needsStorageMigration, type StorageMigrationState } from './storageMigrationPrompt';

const pendingStates: StorageMigrationState[] = ['AWAITING_MIGRATION', 'PARTIALLY_MIGRATED', 'FAILED'];
for (const state of pendingStates) {
  assert.equal(needsStorageMigration({ state, legacyDataExists: true }), true, state);
}

const hiddenStates: StorageMigrationState[] = ['NO_LEGACY_DATA', 'READY_TO_DELETE', 'CLEANED'];
for (const state of hiddenStates) {
  assert.equal(needsStorageMigration({ state, legacyDataExists: true }), false, state);
}

assert.equal(
  needsStorageMigration({ state: 'AWAITING_MIGRATION', legacyDataExists: false }),
  false,
  'the data-browser shortcut stays hidden when no legacy JSON data exists',
);

console.log('Storage migration prompt tests passed.');
