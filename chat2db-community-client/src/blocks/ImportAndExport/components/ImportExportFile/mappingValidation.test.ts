import assert from 'node:assert/strict';
import { getImportMappingIssues, mergeImportColumnMappings } from './mappingValidation';
import type { IImportPreview } from '@/typings/importExport';

const preview: IImportPreview = {
  fileColumns: ['Full Name', 'status'],
  columnMatches: [],
  sampleRows: [],
  missingTableColumns: [],
  targetColumns: [
    { name: 'id', dataType: 'INT', nullable: false, autoIncrement: true, comment: null, defaultValue: null },
    { name: 'name', dataType: 'VARCHAR', nullable: false, autoIncrement: false, comment: null, defaultValue: null },
    { name: 'status', dataType: 'VARCHAR', nullable: false, autoIncrement: false, comment: null, defaultValue: "'NEW'" },
  ],
};
assert.deepEqual(getImportMappingIssues(preview, { 'Full Name': 'name' }).required, []);
assert.deepEqual(
  getImportMappingIssues(preview, { 'Full Name': 'name' }, 'NULL').required.map((column) => column.name),
  ['status'],
);
assert.equal(getImportMappingIssues(preview, { 'Full Name': 'name', status: 'NAME' }).duplicate, true);
assert.equal(getImportMappingIssues(preview, { 'Full Name': undefined }).empty, true);
assert.deepEqual(
  getImportMappingIssues(preview, { status: 'status' }).required.map((column) => column.name),
  ['name'],
);
const refreshedPreview: IImportPreview = {
  ...preview,
  columnMatches: [
    { fileColumn: 'Full Name', matched: false },
    { fileColumn: 'status', tableColumn: 'status', matched: true },
  ],
};
assert.deepEqual(mergeImportColumnMappings(refreshedPreview, { 'Full Name': 'name', status: undefined }), {
  'Full Name': 'name',
  status: undefined,
});
assert.deepEqual(mergeImportColumnMappings(refreshedPreview, { 'Full Name': 'removed_column' }), {
  'Full Name': undefined,
  status: 'status',
});
console.log('Import mapping validation and refresh-preservation checks passed');
