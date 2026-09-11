import assert from 'node:assert/strict';
import test from 'node:test';
import { stageSelectedImportFile } from './fileStaging';

test('stages browser files through multipart upload', async () => {
  const browserFile = { name: 'users.csv' } as File;
  const fileId = await stageSelectedImportFile(
    { file: browserFile, fileName: browserFile.name },
    async ({ file }) => {
      assert.equal(file, browserFile);
      return 'browser-file-id';
    },
    async () => {
      throw new Error('desktop staging must not be used');
    },
  );

  assert.equal(fileId, 'browser-file-id');
});

test('stages desktop paths through the JCEF-only endpoint', async () => {
  const fileId = await stageSelectedImportFile(
    { filePath: '/tmp/users.xlsx', fileName: 'users.xlsx' },
    async () => {
      throw new Error('browser upload must not be used');
    },
    async (request) => {
      assert.deepEqual(request, { sourceFile: '/tmp/users.xlsx', originalFileName: 'users.xlsx' });
      return 'desktop-file-id';
    },
  );

  assert.equal(fileId, 'desktop-file-id');
});

test('rejects incomplete selections', async () => {
  await assert.rejects(() => stageSelectedImportFile({}, async () => '', async () => ''));
});
