import assert from 'node:assert/strict';
import { ImportExportFileType, ImportExportTaskType, ImportExportType } from '@/constants/importExport';
import type { ImportExportDataBoundInfo } from '@/typings/importExport';
import { buildExportTaskParams, DEFAULT_EXPORT_FORM_VALUE } from './exportParams';

const boundInfo: ImportExportDataBoundInfo = {
  dataSourceId: 7,
  databaseName: 'app',
  schemaName: 'public',
  tableName: 'orders',
  type: ImportExportType.EXPORT,
};

// The wizard can no longer hard-code containsHeader, otherwise the restored checkbox is a fake control.
assert.equal(DEFAULT_EXPORT_FORM_VALUE.containsHeader, true);

const withHeader = buildExportTaskParams(boundInfo, DEFAULT_EXPORT_FORM_VALUE, '/tmp/orders.csv');
assert.deepEqual(withHeader, {
  dataSourceId: 7,
  databaseName: 'app',
  schemaName: 'public',
  format: ImportExportFileType.CSV,
  taskType: ImportExportTaskType.TABLE_DATA_EXPORT,
  tableNames: ['orders'],
  containsHeader: true,
  // A CSV export always names its output options; the defaults match the backend defaults.
  encoding: 'UTF-8',
  delimiter: ',',
  exportPath: '/tmp/orders.csv',
});

const customCsv = buildExportTaskParams(
  boundInfo,
  { exportType: ImportExportFileType.CSV, containsHeader: false, encoding: 'GBK', delimiter: ';' },
  '/tmp/orders-gbk.csv',
);
assert.equal(customCsv.encoding, 'GBK');
assert.equal(customCsv.delimiter, ';');
assert.equal(customCsv.containsHeader, false);

const withoutHeader = buildExportTaskParams(
  boundInfo,
  { exportType: ImportExportFileType.XLSX, containsHeader: false },
  '/tmp/orders.xlsx',
);
assert.equal(withoutHeader.containsHeader, false);
assert.equal(withoutHeader.format, ImportExportFileType.XLSX);

const browserDownload = buildExportTaskParams(
  boundInfo,
  { exportType: ImportExportFileType.JSON, containsHeader: false, fileUrl: '/tmp/fallback.json' },
  '',
);
assert.equal(browserDownload.exportPath, '/tmp/fallback.json', 'the export path falls back to the file URL');
assert.equal(browserDownload.encoding, undefined, 'only a CSV export carries CSV output options');
assert.equal(browserDownload.delimiter, undefined, 'only a CSV export carries CSV output options');

assert.equal(buildExportTaskParams(boundInfo, DEFAULT_EXPORT_FORM_VALUE, '').exportPath, undefined,
  'web exports let the backend manage the artifact without a user-entered file URL');
assert.equal(buildExportTaskParams(boundInfo, { ...DEFAULT_EXPORT_FORM_VALUE, fileUrl: '/tmp/old.csv' },
  '/tmp/selected-directory').exportPath, '/tmp/selected-directory',
'the selected desktop directory takes precedence over a legacy form value');

console.log('export task params carry the header option to the backend request');
