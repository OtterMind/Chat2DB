import assert from 'node:assert/strict';
import test from 'node:test';
import { getHeaderMetadataRows } from '../ResultSetTable/headerMetadata';
import { getResultColumnTitle } from '../ResultSetTable/utils/columnTitle';
import { createResultHeaderCustomRender } from '../ResultSetTable/headerRender';
import { retainPinnedResults } from '../../resultTabPinning';
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

test('column metadata contains each available name, type, and comment row', () => {
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

test('missing column metadata uses placeholders to keep all headers aligned', () => {
  assert.deepEqual(getHeaderMetadataRows({ dataType: 'STRING' as any, name: 'id' }), [
    { key: 'fieldName', value: 'id' },
    { key: 'fieldType', value: 'STRING' },
    { key: 'fieldComment', value: '--' },
  ]);
});

test('column metadata normalizes blank type and comment values', () => {
  assert.deepEqual(getHeaderMetadataRows({ dataType: '' as any, name: 'id', comment: '   ' }), [
    { key: 'fieldName', value: 'id' },
    { key: 'fieldType', value: '--' },
    { key: 'fieldComment', value: '--' },
  ]);
});

test('field type and comment rows are enabled by default and can be hidden independently', () => {
  const header = { dataType: 'STRING' as any, name: 'id', comment: 'identifier' };
  assert.deepEqual(getHeaderMetadataRows(header, { showFieldType: false }), [
    { key: 'fieldName', value: 'id' },
    { key: 'fieldComment', value: 'identifier' },
  ]);
  assert.deepEqual(getHeaderMetadataRows(header, { showFieldComment: false }), [
    { key: 'fieldName', value: 'id' },
    { key: 'fieldType', value: 'STRING' },
  ]);
  assert.deepEqual(getHeaderMetadataRows(header, { showFieldType: false, showFieldComment: false }), [
    { key: 'fieldName', value: 'id' },
  ]);
});

test('result headers render available metadata values as lines without hover or labels', () => {
  assert.equal(
    getResultColumnTitle({
      dataType: 'STRING' as any,
      name: 'display_name',
      columnType: 'VARCHAR',
      columnSize: 64,
      comment: 'Displayed account name',
    }),
    'display_name\nVARCHAR(64)\nDisplayed account name',
  );
  assert.equal(getResultColumnTitle({ dataType: 'STRING' as any, name: 'id' }), 'id\nSTRING\n--');
  assert.equal(getResultColumnTitle({ dataType: '' as any, name: 'id', comment: '   ' }), 'id\n--\n--');
});

test('result header custom render fixes row positions and applies semantic colors', () => {
  const render = createResultHeaderCustomRender({
    data: { dataType: 'STRING' as any, name: 'id' },
    fontSize: 13,
    theme: {
      colorText: '#111111',
      colorPrimary: '#1677ff',
      colorTextSecondary: '#888888',
      fontFamily: 'Inter',
    } as any,
  });

  assert.equal(render.expectedHeight, 82);
  assert.equal(render.renderDefault, true);
  assert.deepEqual(
    render.elements.map((element) => ({ text: element.text, fill: element.fill, y: element.y })),
    [
      { text: 'id', fill: '#111111', y: 19 },
      { text: 'STRING', fill: '#1677ff', y: 41 },
      { text: '--', fill: '#888888', y: 63 },
    ],
  );
});

test('pinned result tabs survive replacement while unpinned results are discarded', () => {
  const pinnedResult = { uuid: 'pinned', value: 'old pinned result' };
  const unpinnedResult = { uuid: 'unpinned', value: 'old unpinned result' };
  const nextResult = { uuid: 'next', value: 'new result' };

  assert.deepEqual(
    retainPinnedResults([nextResult], [pinnedResult, unpinnedResult], new Set(['pinned'])),
    [pinnedResult, nextResult],
  );
});

test('incoming result replaces a pinned result with the same key', () => {
  const oldResult = { uuid: 'same', value: 'old result' };
  const updatedResult = { uuid: 'same', value: 'updated result' };

  assert.deepEqual(retainPinnedResults([updatedResult], [oldResult], new Set(['same'])), [updatedResult]);
});
