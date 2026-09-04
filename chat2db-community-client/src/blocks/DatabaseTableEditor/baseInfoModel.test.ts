import assert from 'node:assert/strict';

import {
  buildBaseInfoFormValues,
  filterCollationsByCharset,
  getCompatibleCollationValue,
  getFirstTablePreviewSql,
  isCharsetCollationCompatible,
  mapDatabaseSupportFieldOptions,
} from './baseInfoModel';

const supportField = {
  columnTypes: [],
  charsets: [
    { charsetName: 'utf8', defaultCollationName: 'utf8_general_ci' },
    { charsetName: 'utf8mb4', defaultCollationName: 'utf8mb4_general_ci' },
  ],
  collations: [
    { collationName: 'utf8_general_ci', charset: 'utf8' },
    { collationName: 'utf8mb4_general_ci', charset: 'utf8mb4' },
    { collationName: 'utf8mb4_0900_ai_ci', charset: 'utf8mb4' },
  ],
  indexTypes: [],
  defaultValues: [],
  engineTypes: [],
  supportInvisibleIndex: true,
};

const options = mapDatabaseSupportFieldOptions(supportField);

assert.equal(options.supportInvisibleIndex, true, 'MySQL invisible-index capability survives option mapping');

assert.deepEqual(
  options.charsets[1],
  { value: 'utf8mb4', label: 'utf8mb4', defaultCollationName: 'utf8mb4_general_ci' },
  'charset options preserve default collation metadata',
);

assert.deepEqual(
  options.collations[1],
  { value: 'utf8mb4_general_ci', label: 'utf8mb4_general_ci', charset: 'utf8mb4' },
  'collation options preserve their owning charset',
);

assert.deepEqual(
  buildBaseInfoFormValues({
    name: 'orders',
    comment: null,
    charset: 'utf8mb4',
    collation: 'utf8_general_ci',
    collate: 'utf8mb4_0900_ai_ci',
    engine: 'InnoDB',
    incrementValue: null,
    columnList: [],
    indexList: [],
  }).collation,
  'utf8mb4_0900_ai_ci',
  'BaseInfo reload uses the backend API collate field instead of stale/nonexistent collation',
);

assert.deepEqual(
  filterCollationsByCharset(options.collations, 'utf8mb4').map((option) => option.value),
  ['utf8mb4_general_ci', 'utf8mb4_0900_ai_ci'],
  'collation choices are filtered by the selected charset',
);

assert.equal(
  isCharsetCollationCompatible('utf8mb4', 'utf8_general_ci', options.collations),
  false,
  'selected charset and collation must be compatible before preview/execute',
);

assert.equal(
  isCharsetCollationCompatible('utf8mb4', 'utf8mb4_general_ci', options.collations),
  true,
  'matching charset and collation remain valid',
);

assert.equal(
  getCompatibleCollationValue('utf8mb4', 'utf8_general_ci', options.collations),
  null,
  'changing a column charset clears an incompatible selected collation',
);
assert.equal(
  getCompatibleCollationValue('utf8mb4', 'utf8mb4_general_ci', options.collations),
  'utf8mb4_general_ci',
  'changing a column charset preserves a compatible selected collation',
);

assert.equal(getFirstTablePreviewSql([{ sql: '' }]), null, 'empty table DDL is treated as no changes');
assert.equal(
  getFirstTablePreviewSql([{ sql: ' ALTER TABLE `orders` COLLATE=utf8mb4_general_ci; ' }]),
  'ALTER TABLE `orders` COLLATE=utf8mb4_general_ci;',
  'non-empty table DDL is normalized for preview',
);

console.log('DatabaseTableEditor base info model tests passed');
