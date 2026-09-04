import assert from 'node:assert/strict';
import {
  buildCsvOptionsForTaskSubmit,
  csvOptionsToPreviewParam,
  DEFAULT_CSV_OPTIONS,
  inferImportFileFormat,
  supportsCsvMappingPreview,
  validateCsvOptions,
} from './csvOptions';
import { ImportExportFileType } from '@/constants/importExport';

const options = {
  ...DEFAULT_CSV_OPTIONS,
  encoding: 'AUTO',
  delimiter: '|',
  quote: '"',
  escape: '\\',
  newline: 'CRLF',
};

assert.deepEqual(buildCsvOptionsForTaskSubmit(true, options), options);
assert.equal(csvOptionsToPreviewParam(true, options), JSON.stringify(options));
assert.equal(buildCsvOptionsForTaskSubmit(false, options), undefined);
assert.equal(csvOptionsToPreviewParam(false, options), undefined);
assert.equal(inferImportFileFormat('/tmp/orders.csv'), ImportExportFileType.CSV);
assert.equal(inferImportFileFormat('/tmp/orders.xlsx'), ImportExportFileType.XLSX);
assert.equal(supportsCsvMappingPreview(ImportExportFileType.CSV), true);
assert.equal(supportsCsvMappingPreview(ImportExportFileType.XLSX), false);
assert.equal(supportsCsvMappingPreview(ImportExportFileType.XLS), false);
assert.equal(supportsCsvMappingPreview(ImportExportFileType.JSON), false);
assert.equal(supportsCsvMappingPreview(ImportExportFileType.SQL), false);

assert.throws(
  () => validateCsvOptions({ ...options, delimiter: '"' }),
  /Unsupported CSV option combination/,
  'delimiter cannot match quote',
);
assert.throws(
  () => validateCsvOptions({ ...options, escape: '\n' }),
  /Unsupported CSV option combination/,
  'escape cannot be a line break',
);
