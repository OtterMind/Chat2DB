import assert from 'node:assert/strict';
import { ImportPreviewErrorCode, SKIP_IMPORT_SOURCE_FIELD } from '@/constants/importExport';
import {
  buildImportMappingRows,
  buildInitialImportMapping,
  getDuplicateImportMappings,
  getImportPreviewErrorMessage,
} from './mapping';

const mapping = buildInitialImportMapping(
  ['name', 'email', 'extra_column'],
  [
    { sourceColumn: 'name', targetColumn: 'name' },
    { sourceColumn: 'email', targetColumn: 'email' },
  ],
);

assert.deepEqual(mapping, {
  name: 'name',
  email: 'email',
  extra_column: SKIP_IMPORT_SOURCE_FIELD,
});

assert.deepEqual(
  buildImportMappingRows(['name', 'extra_column'], [{ name: 'id' }, { name: 'name' }, { name: 'note' }], {
    name: 'name',
    extra_column: SKIP_IMPORT_SOURCE_FIELD,
  }),
  [
    { key: 'source:name', kind: 'source', sourceColumn: 'name' },
    { key: 'source:extra_column', kind: 'source', sourceColumn: 'extra_column' },
    { key: 'target:id', kind: 'target', targetColumn: { name: 'id' } },
    { key: 'target:note', kind: 'target', targetColumn: { name: 'note' } },
  ],
);

assert.deepEqual(getDuplicateImportMappings({ ...mapping, extra_column: 'email' }), {
  extra_column: {
    sourceColumn: 'extra_column',
    targetColumn: 'email',
    mappedSource: 'email',
  },
});
assert.deepEqual(getDuplicateImportMappings(mapping), {});
assert.equal(
  getImportPreviewErrorMessage({ errorMessage: 'Duplicate source column: name' }, 'Failed'),
  'Duplicate source column: name',
);
assert.equal(getImportPreviewErrorMessage(new Error('Network unavailable'), 'Failed'), 'Network unavailable');
assert.equal(getImportPreviewErrorMessage({ errorCode: 'SYSTEM_ERROR' }, 'Failed'), 'Failed');
assert.equal(
  getImportPreviewErrorMessage(
    {
      errorCode: ImportPreviewErrorCode.DUPLICATE_SOURCE_COLUMNS,
      errorMessage: 'import.preview.duplicateSourceColumns : no message.',
    },
    'Failed',
    { [ImportPreviewErrorCode.DUPLICATE_SOURCE_COLUMNS]: 'The import file contains duplicate source fields' },
  ),
  'The import file contains duplicate source fields',
);
