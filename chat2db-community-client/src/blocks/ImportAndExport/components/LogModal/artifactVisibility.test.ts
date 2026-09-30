import assert from 'node:assert/strict';
import { ImportExportTaskStatus } from '@/constants/importExport';
import { ITaskArtifact } from '@/typings/importExport';
import {
  getDownloadableTaskArtifacts,
  isFailureDiagnosticArtifactRole,
  shouldShowLegacyPrimaryArtifact,
} from './artifactVisibility';

const artifact = (role: string): ITaskArtifact => ({
  artifactId: `C:\\tasks\\${role.replace(':', '-')}.json`,
  role,
});

const allArtifacts = [
  artifact('OUTPUT'),
  artifact('IMPORT_REPORT'),
  artifact('REJECT_SUMMARY'),
  artifact('REJECT'),
  artifact('REJECT:3ba8907e7a252327488df09c'),
  artifact('CHECKPOINT'),
];

assert.deepEqual(
  getDownloadableTaskArtifacts({
    status: ImportExportTaskStatus.SUCCESS,
    artifacts: allArtifacts,
  }),
  allArtifacts,
  'successful tasks keep every returned artifact available',
);

for (const status of [ImportExportTaskStatus.FAILED, ImportExportTaskStatus.CANCELLED]) {
  assert.deepEqual(
    getDownloadableTaskArtifacts({ status, artifacts: allArtifacts }).map(({ role }) => role),
    ['IMPORT_REPORT', 'REJECT_SUMMARY', 'REJECT', 'REJECT:3ba8907e7a252327488df09c'],
    `${status} tasks expose diagnostics without exposing ordinary outputs`,
  );
}

for (const status of [ImportExportTaskStatus.PENDING, ImportExportTaskStatus.RUNNING]) {
  assert.deepEqual(
    getDownloadableTaskArtifacts({ status, artifacts: allArtifacts }),
    [],
    `${status} tasks do not expose artifacts`,
  );
}

assert.equal(isFailureDiagnosticArtifactRole('REJECTED'), false);
assert.equal(isFailureDiagnosticArtifactRole('REJECT:'), false);
assert.equal(isFailureDiagnosticArtifactRole('OUTPUT'), false);
assert.equal(
  shouldShowLegacyPrimaryArtifact({
    status: ImportExportTaskStatus.SUCCESS,
    artifactId: 'C:\\tasks\\export.csv',
  }),
  true,
);
assert.equal(
  shouldShowLegacyPrimaryArtifact({
    status: ImportExportTaskStatus.FAILED,
    artifactId: 'C:\\tasks\\partial-output.csv',
  }),
  false,
  'a failed task cannot expose an unclassified legacy artifact',
);
assert.equal(
  shouldShowLegacyPrimaryArtifact({
    status: ImportExportTaskStatus.CANCELLED,
    artifactId: 'C:\\tasks\\partial-output.csv',
  }),
  false,
  'a cancelled task cannot expose an unclassified legacy artifact',
);
assert.equal(
  shouldShowLegacyPrimaryArtifact({
    status: ImportExportTaskStatus.SUCCESS,
    artifactId: 'C:\\tasks\\export.csv',
    artifacts: [artifact('OUTPUT')],
  }),
  false,
  'structured artifacts take precedence over the legacy primary artifact',
);
