import { ImportExportFileType } from '@/constants/importExport';
import assert from 'node:assert/strict';
import { hasSelectedImportFile, resolveSelectedImportFileType } from './selection';

assert.equal(hasSelectedImportFile([]), false);
assert.equal(hasSelectedImportFile([{ filePath: '/tmp/users.sql' }]), true);
assert.equal(hasSelectedImportFile([{ file: {} as File }]), true);

const CSV = ImportExportFileType.CSV;
const SQL = ImportExportFileType.SQL;
assert.equal(resolveSelectedImportFileType({ fileName: '05-run-script.sql' }, CSV), SQL);
assert.equal(resolveSelectedImportFileType({ filePath: '/tmp/UPPER.SQL' }, CSV), SQL);
assert.equal(resolveSelectedImportFileType({ file: { name: 'orders.xlsx' } as File }, SQL), ImportExportFileType.XLSX);
assert.equal(resolveSelectedImportFileType({ fileName: 'orders.xls' }, CSV), ImportExportFileType.XLS);
assert.equal(resolveSelectedImportFileType({ fileName: 'orders.JSON' }, SQL), ImportExportFileType.JSON);
assert.equal(resolveSelectedImportFileType({ fileName: 'orders.csv' }, SQL), CSV);
assert.equal(resolveSelectedImportFileType(undefined, SQL), SQL);
assert.equal(resolveSelectedImportFileType({ fileName: 'script.txt' }, SQL), SQL);
