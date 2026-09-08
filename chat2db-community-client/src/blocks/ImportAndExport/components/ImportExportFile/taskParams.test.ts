import assert from 'node:assert/strict';
import { ImportExportFileType, ImportExportTaskType, ImportExportType } from '@/constants/importExport';
import type { ImportExportDataBoundInfo } from '@/typings/importExport';
import { buildTaskParams, initialFileType, type ImportExportFormValue } from './taskParams';

const checkpointableFormats = [
  ImportExportFileType.CSV,
  ImportExportFileType.NDJSON,
  ImportExportFileType.MARKDOWN,
  ImportExportFileType.SQL,
];
const baseForm: ImportExportFormValue = {
  exportType: ImportExportFileType.CSV,
  containsHeader: true,
};

function build(boundInfo: ImportExportDataBoundInfo, overrides: Partial<ImportExportFormValue> = {}) {
  return buildTaskParams({
    boundInfo,
    formValue: { ...baseForm, ...overrides },
    mode: 'ULTRA_FAST',
    sourceFile: 'C:\\imports\\dump.sql',
    exportLocation: 'C:\\exports',
    desktop: true,
    importPreview: null,
    columnMappings: {},
    checkpointableFormats,
  });
}

const tableExport: ImportExportDataBoundInfo = {
  dataSourceId: 1,
  databaseName: 'app',
  schemaName: 'public',
  tableName: 'orders',
  targetScope: 'TABLE',
  type: ImportExportType.EXPORT,
};
assert.equal(initialFileType(tableExport), ImportExportFileType.CSV);
assert.deepEqual(build(tableExport, { checkpointRows: 10000 }), {
  dataSourceId: 1,
  databaseName: 'app',
  schemaName: 'public',
  format: ImportExportFileType.CSV,
  mode: 'ULTRA_FAST',
  taskType: ImportExportTaskType.TABLE_DATA_EXPORT,
  tableNames: ['orders'],
  containsHeader: true,
  exportPath: 'C:\\exports',
  compression: undefined,
  checkpointRows: 10000,
});

const schemaExport: ImportExportDataBoundInfo = {
  dataSourceId: 1,
  databaseName: 'app',
  schemaName: 'reporting',
  targetScope: 'SCHEMA',
  type: ImportExportType.EXPORT,
  fileType: ImportExportFileType.SQL,
  sqlExportScope: 'ALL',
};
assert.equal(initialFileType(schemaExport), ImportExportFileType.SQL);
assert.deepEqual(build(schemaExport, { exportType: ImportExportFileType.SQL, compression: 'GZIP' }), {
  dataSourceId: 1,
  databaseName: 'app',
  schemaName: 'reporting',
  format: ImportExportFileType.SQL,
  mode: 'ULTRA_FAST',
  taskType: ImportExportTaskType.SQL_EXPORT,
  tableNames: undefined,
  scope: 'ALL',
  containData: true,
  containsHeader: true,
  exportPath: 'C:\\exports',
});

const databaseImport: ImportExportDataBoundInfo = {
  dataSourceId: 1,
  databaseName: 'app',
  targetScope: 'DATABASE',
  type: ImportExportType.IMPORT,
  fileType: ImportExportFileType.SQL,
};
assert.deepEqual(build(databaseImport, { exportType: ImportExportFileType.SQL }), {
  dataSourceId: 1,
  databaseName: 'app',
  schemaName: undefined,
  format: ImportExportFileType.SQL,
  mode: 'ULTRA_FAST',
  taskType: ImportExportTaskType.SQL_FILE_IMPORT,
  tableName: undefined,
  sourceFile: 'C:\\imports\\dump.sql',
  options: undefined,
});

const tableImport: ImportExportDataBoundInfo = {
  ...tableExport,
  type: ImportExportType.IMPORT,
};
const mappedImport = buildTaskParams({
  boundInfo: tableImport,
  formValue: { ...baseForm, delimiter: ';', onError: 'SKIP', maxErrors: 5 },
  mode: 'STANDARD',
  sourceFile: 'C:\\imports\\orders.csv',
  exportLocation: '',
  desktop: true,
  importPreview: {
    fileColumns: ['order_id'],
    columnMatches: [],
    missingTableColumns: [],
    sampleRows: [],
  },
  columnMappings: { order_id: 'id' },
  checkpointableFormats,
});
assert.equal(mappedImport.taskType, ImportExportTaskType.DATA_FILE_IMPORT);
assert.equal('tableName' in mappedImport ? mappedImport.tableName : undefined, 'orders');
assert.deepEqual('options' in mappedImport ? mappedImport.options : undefined, {
  charset: undefined,
  delimiter: ';',
  quoteChar: undefined,
  skipRows: undefined,
  nullString: undefined,
  onError: 'SKIP',
  maxErrors: 5,
  columnMappings: [{ sourceColumn: 'order_id', targetColumn: 'id' }],
});

const confirmedParallelImport = buildTaskParams({
  boundInfo: tableImport,
  formValue: baseForm,
  mode: 'ULTRA_FAST',
  sourceFile: 'C:\\imports\\orders.csv',
  exportLocation: '',
  desktop: true,
  importPreview: null,
  columnMappings: {},
  checkpointableFormats,
  confirmedNoStrongRelations: true,
});
assert.equal(
  'confirmedNoStrongRelations' in confirmedParallelImport
    ? confirmedParallelImport.confirmedNoStrongRelations
    : undefined,
  true,
);

console.log('Import/export wizard task parameter tests passed');
