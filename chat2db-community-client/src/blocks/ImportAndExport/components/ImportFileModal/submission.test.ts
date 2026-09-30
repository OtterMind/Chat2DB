import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import {
  createClientSubmissionId,
  getServerStagedImportFileIds,
  hasServerStagedImportFiles,
  importSubmissionIdentityAfterFailure,
  isUnknownSubmissionResponse,
  prepareImportSubmission,
  prepareWebImportParams,
} from './submission';

const file = { name: 'users.json' } as File;
const params: import('@/service/importExport').ImportTaskParams = {
  dataSourceId: 1,
  databaseName: 'app',
  tableName: 'users',
  taskType: ImportExportTaskType.DATA_FILE_IMPORT,
  format: ImportExportFileType.JSON,
  sourceFile: '',
};

prepareWebImportParams(params, file, async ({ file: uploadedFile }) => {
  assert.equal(uploadedFile, file);
  return 'staged-file-id';
}).then((prepared) => {
  assert.deepEqual(prepared, {
    ...params,
    sourceFile: undefined,
    fileId: 'staged-file-id',
    displayFileName: 'users.json',
  });
});

assert.equal(hasServerStagedImportFiles(params), false);
assert.equal(hasServerStagedImportFiles({ ...params, sourceFile: undefined, fileId: 'staged-file-id' }), true);
assert.equal(
  hasServerStagedImportFiles({
    ...params,
    tableSources: [
      {
        tableName: 'users',
        fileId: 'staged-users',
        format: ImportExportFileType.CSV,
      },
    ],
  }),
  true,
);
assert.deepEqual(
  getServerStagedImportFileIds({
    ...params,
    fileId: 'top-level',
    tableSources: [
      { tableName: 'users', fileId: 'table-users', format: ImportExportFileType.CSV },
      { tableName: 'archive-users', fileId: 'table-users', format: ImportExportFileType.CSV },
      { tableName: 'without-file', format: ImportExportFileType.CSV },
    ],
  }),
  ['top-level', 'table-users'],
);

let generatedSubmissionIds = 0;
const createSubmissionId = () => `attempt-${++generatedSubmissionIds}`;
const firstSubmission = prepareImportSubmission(
  { ...params, sourceFile: undefined, fileId: 'staged-users' },
  undefined,
  createSubmissionId,
);
const retainedAfterUnknown = importSubmissionIdentityAfterFailure(
  firstSubmission.identity,
  new Error('connection reset'),
);
const unchangedRetry = prepareImportSubmission(firstSubmission.params, retainedAfterUnknown, createSubmissionId);
const replacedFileRetry = prepareImportSubmission(
  { ...firstSubmission.params, fileId: 'staged-users-replacement' },
  retainedAfterUnknown,
  createSubmissionId,
);
const changedTargetRetry = prepareImportSubmission(
  { ...firstSubmission.params, tableName: 'archived_users' },
  firstSubmission.identity,
  createSubmissionId,
);
const changedOptionsRetry = prepareImportSubmission(
  { ...firstSubmission.params, options: { delimiter: ';' } },
  firstSubmission.identity,
  createSubmissionId,
);
const clearedAfterKnownFailure = importSubmissionIdentityAfterFailure(firstSubmission.identity, {
  errorCode: 'task.import.invalid',
});
const proposedAfterKnownFailure = createSubmissionId();
const retryAfterKnownFailure = prepareImportSubmission(
  { ...firstSubmission.params, clientSubmissionId: proposedAfterKnownFailure },
  clearedAfterKnownFailure,
  createSubmissionId,
);
assert.equal(firstSubmission.params.clientSubmissionId, 'attempt-1');
assert.equal(unchangedRetry.params.clientSubmissionId, firstSubmission.params.clientSubmissionId);
assert.equal(replacedFileRetry.params.clientSubmissionId, 'attempt-2');
assert.equal(changedTargetRetry.params.clientSubmissionId, 'attempt-3');
assert.equal(changedOptionsRetry.params.clientSubmissionId, 'attempt-4');
assert.equal(retryAfterKnownFailure.params.clientSubmissionId, 'attempt-5');
assert.equal(generatedSubmissionIds, 5);
assert.ok(createClientSubmissionId().length > 0);
assert.equal(isUnknownSubmissionResponse(new Error('connection reset')), true);
assert.equal(isUnknownSubmissionResponse('timeout_error:/api/tasks/import'), true);
assert.equal(isUnknownSubmissionResponse({ errorCode: 'task.import.invalid' }), false);
assert.equal(retainedAfterUnknown, firstSubmission.identity);
assert.equal(importSubmissionIdentityAfterFailure(firstSubmission.identity, 'timeout_error:/api/tasks/import'),
  firstSubmission.identity);
assert.equal(importSubmissionIdentityAfterFailure(firstSubmission.identity, { errorCode: 'task.import.invalid' }),
  undefined);

const modalSource = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
assert.match(modalSource, /<Button disabled=\{submitting\} onClick=\{closeModal\}>/);
assert.match(modalSource, /closable=\{!submitting\}/);
assert.match(modalSource, /if \(!submitting\) closeModal\(\);/);
assert.match(modalSource, /getValues\(proposedClientSubmissionId\)/);
assert.match(modalSource, /prepareImportSubmission\(params as ImportTaskParams, importSubmissionIdentityRef\.current\)/);
assert.match(modalSource, /const unknownResponse = isUnknownSubmissionResponse\(error\)/);
