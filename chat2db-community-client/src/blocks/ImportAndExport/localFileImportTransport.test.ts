import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createRequestFormData } from '@/service/formData';
import { resolveImportTaskTransport } from '@/service/importTaskTransport';
import { resolveLocalImportSource, toWebLocalFileSelection } from '@/utils/localImportFile';
import { createPendingSubmissionGuard } from './submissionGuard';

const dom = new JSDOM('<!doctype html><html><body></body></html>');
const originalGlobals = {
  Blob: globalThis.Blob,
  File: globalThis.File,
  FormData: globalThis.FormData,
};

Object.assign(globalThis, {
  Blob: dom.window.Blob,
  File: dom.window.File,
  FormData: dom.window.FormData,
});

after(() => {
  Object.assign(globalThis, originalGlobals);
  dom.window.close();
});

function readBlob(blob: Blob, method: 'arrayBuffer' | 'text'): Promise<ArrayBuffer | string> {
  return new Promise((resolve, reject) => {
    const reader = new dom.window.FileReader();
    reader.onerror = () => reject(reader.error);
    reader.onload = () => resolve(reader.result as ArrayBuffer | string);
    if (method === 'arrayBuffer') {
      reader.readAsArrayBuffer(blob);
    } else {
      reader.readAsText(blob);
    }
  });
}

test('standard browser File bytes and name reach the multipart import submission', async () => {
  const bytes = new Uint8Array([0x69, 0x64, 0x0a, 0x31, 0x0a]);
  const browserFile = new dom.window.File([bytes], 'people.csv', { type: 'text/csv' });
  const selection = toWebLocalFileSelection({ name: browserFile.name, originFileObj: browserFile });

  assert.strictEqual(selection.file, browserFile);
  assert.equal(selection.filePath, undefined);

  const source = resolveLocalImportSource(selection);
  const transport = resolveImportTaskTransport(
    {
      dataSourceId: 42,
      databaseName: 'app',
      schemaName: 'public',
      tableName: 'people',
      taskType: 'DATA_FILE_IMPORT',
      format: 'CSV',
      ...source,
    },
    false,
  );

  assert.equal(transport.kind, 'upload');
  if (transport.kind !== 'upload') return;

  const formData = createRequestFormData(transport.params);
  const transmittedFile = formData.get('file');
  const transmittedRequest = formData.get('request');

  assert.ok(transmittedFile instanceof dom.window.File);
  assert.equal(transmittedFile.name, 'people.csv');
  assert.deepEqual(
    new Uint8Array((await readBlob(transmittedFile, 'arrayBuffer')) as ArrayBuffer),
    bytes,
  );
  assert.ok(transmittedRequest instanceof dom.window.Blob);
  assert.equal(transmittedRequest.type, 'application/json');
  assert.deepEqual(JSON.parse((await readBlob(transmittedRequest, 'text')) as string), {
    dataSourceId: 42,
    databaseName: 'app',
    schemaName: 'public',
    tableName: 'people',
    taskType: 'DATA_FILE_IMPORT',
    format: 'CSV',
    sourceFile: '',
    displayFileName: 'people.csv',
  });
});

test('desktop imports keep the selected local path and do not use multipart transport', () => {
  const source = resolveLocalImportSource({
    fileName: 'orders.sql',
    filePath: 'C:\\imports\\orders.sql',
  });
  const transport = resolveImportTaskTransport(
    {
      dataSourceId: 7,
      taskType: 'SQL_FILE_IMPORT',
      format: 'SQL',
      ...source,
    },
    true,
  );

  assert.equal(transport.kind, 'path');
  if (transport.kind !== 'path') return;
  assert.equal(transport.params.sourceFile, 'C:\\imports\\orders.sql');
  assert.equal(transport.params.displayFileName, 'orders.sql');
  assert.equal('file' in transport.params, false);
});

test('pending submission guard accepts only one request until it settles', async () => {
  const guard = createPendingSubmissionGuard();
  let calls = 0;
  let resolveRequest: (value: number) => void = () => undefined;
  const request = new Promise<number>((resolve) => {
    resolveRequest = resolve;
  });

  const first = guard.run(() => {
    calls += 1;
    return request;
  });
  const duplicate = guard.run(() => {
    calls += 1;
    return Promise.resolve(2);
  });

  assert.equal(calls, 1);
  assert.equal(duplicate, undefined);
  assert.equal(guard.isPending(), true);
  resolveRequest(1);
  assert.equal(await first, 1);
  assert.equal(guard.isPending(), false);

  assert.equal(await guard.run(() => Promise.resolve(3)), 3);
});

test('pending submission guard releases after a rejected request', async () => {
  const guard = createPendingSubmissionGuard();

  await assert.rejects(guard.run(() => Promise.reject(new Error('failed'))));

  assert.equal(guard.isPending(), false);
  assert.equal(await guard.run(() => Promise.resolve('retried')), 'retried');
});
