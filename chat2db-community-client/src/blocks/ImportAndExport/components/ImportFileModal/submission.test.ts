import assert from 'node:assert/strict';
import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import { prepareWebImportParams } from './submission';

const file = { name: 'users.json' } as File;
const params = {
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
