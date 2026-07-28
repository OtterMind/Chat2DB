import assert from 'node:assert/strict';
import { runExportConnections } from './exportConnectionsFlow';

const params = { datasourceIds: [1, 2] };

async function shouldReportSavedExport() {
  const events: string[] = [];
  let savedFile: { fileName: string; fileContent: string; fileType: string } | undefined;

  const outcome = await runExportConnections(params, {
    exportDataSource: async () => ({ message: '{"connections":[]}' }),
    saveFile: async (file) => {
      savedFile = file;
      return { path: '/tmp/export_chat2db_connections.json', size: 18 };
    },
    onExporting: () => events.push('exporting'),
    onSaved: () => events.push('saved'),
    onCancelled: () => events.push('cancelled'),
    onFailed: () => events.push('failed'),
  });

  assert.equal(outcome, 'saved');
  assert.deepEqual(events, ['exporting', 'saved']);
  assert.deepEqual(savedFile, {
    fileName: 'export_chat2db_connections',
    fileContent: '{"connections":[]}',
    fileType: 'json',
  });
}

async function shouldReportCancelledExport() {
  const events: string[] = [];

  const outcome = await runExportConnections(params, {
    exportDataSource: async () => ({ message: '{}' }),
    saveFile: async () => null,
    onExporting: () => events.push('exporting'),
    onSaved: () => events.push('saved'),
    onCancelled: () => events.push('cancelled'),
    onFailed: () => events.push('failed'),
  });

  assert.equal(outcome, 'cancelled');
  assert.deepEqual(events, ['exporting', 'cancelled']);
}

async function shouldReportExportFailure() {
  const events: string[] = [];

  const outcome = await runExportConnections(params, {
    exportDataSource: async () => {
      throw new Error('request failed');
    },
    saveFile: async () => {
      throw new Error('must not save');
    },
    onExporting: () => events.push('exporting'),
    onSaved: () => events.push('saved'),
    onCancelled: () => events.push('cancelled'),
    onFailed: () => events.push('failed'),
  });

  assert.equal(outcome, 'failed');
  assert.deepEqual(events, ['exporting', 'failed']);
}

async function shouldReportSaveFailure() {
  const events: string[] = [];

  const outcome = await runExportConnections(params, {
    exportDataSource: async () => ({ message: '{}' }),
    saveFile: async () => {
      throw new Error('save failed');
    },
    onExporting: () => events.push('exporting'),
    onSaved: () => events.push('saved'),
    onCancelled: () => events.push('cancelled'),
    onFailed: () => events.push('failed'),
  });

  assert.equal(outcome, 'failed');
  assert.deepEqual(events, ['exporting', 'failed']);
}

async function main() {
  await shouldReportSavedExport();
  await shouldReportCancelledExport();
  await shouldReportExportFailure();
  await shouldReportSaveFailure();

  console.log('Export connections flow tests passed');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
