import assert from 'node:assert/strict';
import test from 'node:test';
import { getHeaderMetadataRows } from '../ResultSetTable/headerMetadata';
import { getResultColumnTitle } from '../ResultSetTable/utils/columnTitle';
import {
  applyResultSearchVisibilityAction,
  getResultSearchVisibility,
  RESULT_SEARCH_VISIBLE_BY_DEFAULT,
} from './resultSearchVisibility';

test('result search stays hidden until explicitly opened', () => {
  assert.equal(RESULT_SEARCH_VISIBLE_BY_DEFAULT, false);
  assert.equal(getResultSearchVisibility('open'), true);
  assert.equal(getResultSearchVisibility('close'), false);
});

test('result search shortcut opens, prevents browser find, and focuses after mounting', () => {
  const calls: string[] = [];
  applyResultSearchVisibilityAction('open', {
    close: () => calls.push('close'),
    defer: (callback) => {
      calls.push('defer');
      callback();
    },
    focus: () => calls.push('focus'),
    open: () => calls.push('open'),
    preventDefault: () => calls.push('preventDefault'),
  });

  assert.deepEqual(calls, ['open', 'preventDefault', 'defer', 'focus']);
});

test('result search escape and close action use the same close lifecycle', () => {
  const calls: string[] = [];
  applyResultSearchVisibilityAction('close', {
    close: () => calls.push('close'),
    defer: () => calls.push('defer'),
    focus: () => calls.push('focus'),
    open: () => calls.push('open'),
    preventDefault: () => calls.push('preventDefault'),
  });

  assert.deepEqual(calls, ['close']);
});

test('column metadata always contains name, type, and comment rows', () => {
  assert.deepEqual(
    getHeaderMetadataRows({
      dataType: 'STRING' as any,
      name: 'display_name',
      columnType: 'VARCHAR',
      columnSize: 64,
      comment: 'Displayed account name',
    }),
    [
      { key: 'fieldName', value: 'display_name' },
      { key: 'fieldType', value: 'VARCHAR(64)' },
      { key: 'fieldComment', value: 'Displayed account name' },
    ],
  );
});

test('missing column metadata still preserves all three rows', () => {
  assert.deepEqual(getHeaderMetadataRows({ dataType: 'STRING' as any, name: 'id' }), [
    { key: 'fieldName', value: 'id' },
    { key: 'fieldType', value: 'STRING' },
    { key: 'fieldComment', value: '-' },
  ]);
});

test('column metadata normalizes blank type and comment values', () => {
  assert.deepEqual(getHeaderMetadataRows({ dataType: '' as any, name: 'id', comment: '   ' }), [
    { key: 'fieldName', value: 'id' },
    { key: 'fieldType', value: '-' },
    { key: 'fieldComment', value: '-' },
  ]);
});

test('result headers show available comments without empty parentheses', () => {
  assert.equal(getResultColumnTitle({ name: 'id', comment: null }), 'id');
  assert.equal(getResultColumnTitle({ name: 'name', comment: 'Display name' }), 'name(Display na...)');
});
