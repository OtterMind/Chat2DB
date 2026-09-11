import assert from 'node:assert/strict';
import {
  buildCsvOptionsForTaskSubmit,
  csvOptionsToPreviewParam,
  DEFAULT_CSV_OPTIONS,
  inferImportFileFormat,
  getCsvDateTimeExamples,
  supportsCsvMappingPreview,
  validateCsvOptions,
} from './csvOptions';
import { ImportExportFileType } from '@/constants/importExport';

assert.equal(DEFAULT_CSV_OPTIONS.encoding, 'AUTO');

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
assert.equal(validateCsvOptions({ ...options, encoding: 'koi8-r' }).encoding, 'KOI8-R');
assert.equal(validateCsvOptions({ ...options, delimiter: '^' }).delimiter, '^');
assert.deepEqual(
  getCsvDateTimeExamples({
    ...options,
    dateOrder: 'DMY',
    dateTimeOrder: 'DATE_TIME',
    dateDelimiter: '/',
    yearDelimiter: '-',
    timeDelimiter: ':',
  }),
  ['24/8-23 15:30:38', '24/8-2023 15:30:38', '24/Aug-23 15:30:38', '24/August-23 15:30:38'],
);

assert.throws(
  () => validateCsvOptions({ ...options, delimiter: '"' }),
  (error: unknown) => (error as { errorCode?: string }).errorCode === 'import.preview.invalidCsvOptions',
  'delimiter cannot match quote',
);
assert.throws(
  () => validateCsvOptions({ ...options, escape: '\n' }),
  (error: unknown) => (error as { errorCode?: string }).errorCode === 'import.preview.invalidCsvOptions',
  'escape cannot be a line break',
);
