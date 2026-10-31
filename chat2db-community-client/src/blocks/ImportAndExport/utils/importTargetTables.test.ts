import assert from 'node:assert/strict';
import test from 'node:test';
import {
  appendImportTargetTableOptions,
  planImportTargetTableSwitch,
  shouldLoadMoreImportTargetTables,
  toImportTargetTableOptions,
} from './importTargetTables';

test('clears the mapping and asks for a new preview when the target table changes', () => {
  const plan = planImportTargetTableSwitch({
    previousTableName: 'e2e_import',
    nextTableName: 'orders',
    fileSelected: true,
  });
  assert.deepEqual(plan, {
    tableName: 'orders',
    mappingCleared: true,
    previewReloadRequired: true,
    notifyMappingCleared: true,
  });
});

test('switches silently before a file is selected and ignores a same-table pick', () => {
  assert.deepEqual(
    planImportTargetTableSwitch({ previousTableName: 'e2e_import', nextTableName: 'orders', fileSelected: false }),
    {
      tableName: 'orders',
      mappingCleared: true,
      previewReloadRequired: true,
      notifyMappingCleared: false,
    },
  );
  assert.deepEqual(
    planImportTargetTableSwitch({ previousTableName: 'orders', nextTableName: 'orders', fileSelected: true }),
    {
      tableName: 'orders',
      mappingCleared: false,
      previewReloadRequired: false,
      notifyMappingCleared: false,
    },
    'picking the current table must not drop the mapping',
  );
  assert.deepEqual(
    planImportTargetTableSwitch({ previousTableName: 'orders', nextTableName: '', fileSelected: true }),
    {
      tableName: 'orders',
      mappingCleared: false,
      previewReloadRequired: false,
      notifyMappingCleared: false,
    },
    'clearing the selection keeps the previous target',
  );
});

test('builds the dropdown options from the paged table list', () => {
  assert.deepEqual(toImportTargetTableOptions([{ name: 'orders', tableType: 'TABLE' }, { name: ' users ', tableType: 'TABLE' }]), [
    { value: 'orders', label: 'orders' },
    { value: 'users', label: 'users' },
  ]);
  assert.deepEqual(toImportTargetTableOptions([{ name: '', tableType: 'TABLE' }, { name: 'orders', tableType: 'TABLE' }, { name: 'orders', tableType: 'VIEW' }]), [
    { value: 'orders', label: 'orders' },
  ]);
  assert.deepEqual(toImportTargetTableOptions(undefined), []);
});

test('appends a page without duplicating tables already listed', () => {
  const firstPage = toImportTargetTableOptions([{ name: 'a', tableType: 'TABLE' }, { name: 'b', tableType: 'TABLE' }]);
  const secondPage = toImportTargetTableOptions([{ name: 'b', tableType: 'TABLE' }, { name: 'c', tableType: 'TABLE' }]);
  assert.deepEqual(appendImportTargetTableOptions(firstPage, secondPage).map((option) => option.value), ['a', 'b', 'c']);
  const untouched = appendImportTargetTableOptions(firstPage, []);
  assert.equal(untouched, firstPage, 'an empty page keeps the current options');
});

test('loads another page only while one exists and nothing is in flight', () => {
  assert.equal(shouldLoadMoreImportTargetTables({ hasNextPage: true, loading: false }), true);
  assert.equal(shouldLoadMoreImportTargetTables({ hasNextPage: true, loading: true }), false);
  assert.equal(shouldLoadMoreImportTargetTables({ hasNextPage: false, loading: false }), false);
});
