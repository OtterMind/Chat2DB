import assert from 'node:assert/strict';
import {
  attachmentFileName,
  connectionImportContent,
  createMultipartFormData,
  downloadHttpGetAttachment,
  downloadHttpAttachment,
  hasHostImportContent,
  openHostArtifact,
  selectHostExportPath,
  selectedLocalFile,
  withHostImportFile,
} from './hostFileTransfer';

async function verifiesBrowserAttachmentDownload() {
  const calls: string[] = [];
  const anchor = {
    style: {},
    href: '',
    download: '',
    click: () => calls.push('click'),
    remove: () => calls.push('remove'),
  } as unknown as HTMLAnchorElement;
  const environment = {
    fetch: (async () =>
      new Response(new Blob(['zip-bytes']), {
        headers: {
          'content-type': 'application/zip',
          'content-disposition': 'attachment; filename="classes.zip"',
        },
      })) as typeof fetch,
    document: {
      createElement: () => anchor,
      body: {
        appendChild: () => calls.push('append'),
      },
    } as unknown as Document,
    url: {
      createObjectURL: () => {
        calls.push('create-url');
        return 'blob:classes';
      },
      revokeObjectURL: (url: string) => calls.push(`revoke:${url}`),
    } as unknown as typeof URL,
  };

  await downloadHttpAttachment('/api/rdb/table/generate/class', {}, environment);
  assert.equal(anchor.href, 'blob:classes');
  assert.equal(anchor.download, 'classes.zip');
  assert.deepEqual(calls, ['create-url', 'append', 'click', 'remove', 'revoke:blob:classes']);
}

async function verifiesStreamingGetDownload() {
  const calls: string[] = [];
  const anchor = {
    style: {},
    href: '',
    download: 'unset',
    click: () => calls.push('click'),
    remove: () => calls.push('remove'),
  } as unknown as HTMLAnchorElement;
  const environment = {
    document: {
      createElement: () => anchor,
      body: {
        appendChild: () => calls.push('append'),
      },
    } as unknown as Document,
    location: {
      href: 'http://127.0.0.1:4200/workspace',
      origin: 'http://127.0.0.1:4200',
    },
    url: URL,
  };

  await downloadHttpGetAttachment('/api/task/download?id=9', environment);
  assert.equal(anchor.href, 'http://127.0.0.1:4200/api/task/download?id=9');
  assert.equal(anchor.download, '');
  assert.deepEqual(calls, ['append', 'click', 'remove']);

  await assert.rejects(
    downloadHttpGetAttachment('https://example.invalid/export.sql', environment),
    /same-origin HTTP/,
  );
  assert.deepEqual(calls, ['append', 'click', 'remove']);
}

async function verifiesHostFileSelection() {
  const browserFile = { name: 'items.csv' } as File;
  assert.equal(selectedLocalFile({ filePath: '/tmp/items.csv', file: browserFile }), '/tmp/items.csv');
  assert.equal(selectedLocalFile({ file: browserFile }), browserFile);
  assert.deepEqual(withHostImportFile({ dataSourceId: 7 }, '/tmp/items.csv', true), {
    dataSourceId: 7,
    fileName: '/tmp/items.csv',
  });
  assert.deepEqual(withHostImportFile({ dataSourceId: 7 }, browserFile, false), {
    dataSourceId: 7,
    file: browserFile,
  });
}

function verifiesMultipartSerialization() {
  const file = new File(['id,name\n1,alpha'], 'items.csv', { type: 'text/csv' });
  const formData = createMultipartFormData({
    file,
    dataSourceId: 7,
    schemaName: undefined,
    tableName: null,
    containsHeader: false,
    offset: 0,
    label: '',
  });

  assert.equal(formData.get('file'), file);
  assert.equal(formData.get('dataSourceId'), '7');
  assert.equal(formData.has('schemaName'), false);
  assert.equal(formData.has('tableName'), false);
  assert.equal(formData.get('containsHeader'), 'false');
  assert.equal(formData.get('offset'), '0');
  assert.equal(formData.get('label'), '');
}

function verifiesConnectionImportState() {
  const browserFile = new File(['{}'], 'connections.json', { type: 'application/json' });
  assert.equal(connectionImportContent([]), undefined);
  assert.equal(connectionImportContent([{ file: browserFile }]), browserFile);
  assert.deepEqual(connectionImportContent([{ filePath: '/tmp/connections.json' }]), ['/tmp/connections.json']);
  assert.equal(hasHostImportContent(undefined), false);
  assert.equal(hasHostImportContent('   '), false);
  assert.equal(hasHostImportContent([]), false);
  assert.equal(hasHostImportContent(browserFile), true);
  assert.equal(hasHostImportContent(['/tmp/connections.json']), true);
}

async function verifiesExportPathSelection() {
  let pickerCalls = 0;
  const picker = async () => {
    pickerCalls += 1;
    return '/tmp/export';
  };
  assert.equal(await selectHostExportPath(false, picker), '');
  assert.equal(pickerCalls, 0);
  assert.equal(await selectHostExportPath(true, picker), '/tmp/export');
  assert.equal(pickerCalls, 1);
}

async function verifiesArtifactHostRouting() {
  const calls: string[] = [];
  await openHostArtifact('/tmp/export.csv', {
    desktop: true,
    revealInExplorer: async (path) => {
      calls.push(`reveal:${path}`);
    },
    download: async (url) => {
      calls.push(`download:${url}`);
    },
  });
  await openHostArtifact('/api/task/download?id=9', {
    desktop: false,
    revealInExplorer: async (path) => {
      calls.push(`reveal:${path}`);
    },
    download: async (url) => {
      calls.push(`download:${url}`);
    },
  });
  assert.deepEqual(calls, ['reveal:/tmp/export.csv', 'download:/api/task/download?id=9']);
}

function verifiesAttachmentNames() {
  assert.equal(attachmentFileName('attachment; filename="classes.zip"'), 'classes.zip');
  assert.equal(attachmentFileName("attachment; filename*=UTF-8''mysql%20export.csv"), 'mysql export.csv');
  assert.equal(attachmentFileName('attachment; filename="../../unsafe.sql"'), 'unsafe.sql');
  assert.equal(attachmentFileName(null), 'chat2db-export');
}

async function main() {
  await verifiesBrowserAttachmentDownload();
  await verifiesStreamingGetDownload();
  await verifiesHostFileSelection();
  verifiesMultipartSerialization();
  verifiesConnectionImportState();
  await verifiesExportPathSelection();
  await verifiesArtifactHostRouting();
  verifiesAttachmentNames();
  console.log('Host file transfer tests passed');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
