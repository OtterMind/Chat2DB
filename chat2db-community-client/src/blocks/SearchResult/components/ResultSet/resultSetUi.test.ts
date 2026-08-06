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
import {
  canFreezeResultColumns,
  canHideResultColumn,
  canHideResultColumns,
  getNextFrozenResultColumnFields,
  getResultCellMetaAtTableColumn,
  getResultColumnDisplayOrder,
  getResultFrozenColumnCount,
  hideResultColumnFields,
  mergeResultColumnOrderFromDisplay,
  orderResultColumns,
  updateHiddenResultColumnFields,
} from '../ResultSetTable/columnState';
import { resolveResultSelectionActiveCell } from '../ResultSetTable/selectionState';
import {
  isResultHeaderContext,
  joinContextMenuGroups,
} from '../ResultSetTable/event/onContextmenuCell/menuGroups';

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

test('column metadata does not duplicate a type size already present in the JDBC type', () => {
  assert.deepEqual(
    getHeaderMetadataRows({
      dataType: 'DECIMAL' as any,
      name: 'amount',
      columnType: 'DECIMAL(10,2)',
      columnSize: 10,
    }),
    [
      { key: 'fieldName', value: 'amount' },
      { key: 'fieldType', value: 'DECIMAL(10,2)' },
      { key: 'fieldComment', value: '--' },
    ],
  );
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
    render.elements.map((element) => ({
      text: element.text,
      fill: element.fill,
      fontSize: element.fontSize,
      fontWeight: element.fontWeight,
      y: element.y,
    })),
    [
      { text: 'id', fill: '#111111', fontSize: 14, fontWeight: 600, y: 19 },
      { text: 'STRING', fill: '#1677ff', fontSize: 13, fontWeight: 400, y: 41 },
      { text: '--', fill: '#888888', fontSize: 13, fontWeight: 400, y: 63 },
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

const resultColumns = ['1', '2', '3', '4'].map((field) => ({ field }));

test('column visibility always keeps at least one data column visible', () => {
  let hiddenFields = new Set<string>();
  hiddenFields = updateHiddenResultColumnFields(['1', '2'], hiddenFields, '1', false);
  assert.deepEqual([...hiddenFields], ['1']);
  assert.equal(canHideResultColumn(['1', '2'], hiddenFields, '2'), false);
  assert.deepEqual(
    [...updateHiddenResultColumnFields(['1', '2'], hiddenFields, '2', false)],
    ['1'],
  );
});

test('contiguous and jump-selected columns hide together in source order without hiding every column', () => {
  assert.deepEqual(
    [...hideResultColumnFields(['1', '2', '3', '4'], new Set(), ['4', '2'])],
    ['2', '4'],
  );
  assert.deepEqual(
    [...hideResultColumnFields(['1', '2', '3', '4'], new Set(), ['2', '3'])],
    ['2', '3'],
  );

  const hiddenFields = new Set(['2', '4']);
  assert.equal(canHideResultColumns(['1', '2', '3', '4'], hiddenFields, ['1', '3']), false);
  assert.deepEqual(
    [...hideResultColumnFields(['1', '2', '3', '4'], hiddenFields, ['3', '1'])],
    ['2', '4'],
  );
});

test('column and display actions are scoped to result headers', () => {
  assert.equal(isResultHeaderContext(0, 1), true);
  assert.equal(isResultHeaderContext(1, 1), false);
  assert.equal(isResultHeaderContext(1, 0), false);
});

test('jump-selected columns freeze together without changing their relative order', () => {
  const frozenFields = getNextFrozenResultColumnFields(resultColumns, [], ['4', '2']);
  assert.deepEqual(frozenFields, ['2', '4']);
  assert.deepEqual(getResultColumnDisplayOrder(['1', '2', '3', '4'], frozenFields), ['2', '4', '1', '3']);
  assert.deepEqual(
    orderResultColumns(resultColumns, getResultColumnDisplayOrder(['1', '2', '3', '4'], frozenFields)).map(
      (column) => column.field,
    ),
    ['2', '4', '1', '3'],
  );
});

test('each later freeze batch is inserted before previously frozen columns', () => {
  const firstDisplay = orderResultColumns(resultColumns, ['2', '4', '1', '3']);
  const frozenFields = getNextFrozenResultColumnFields(firstDisplay, ['2', '4'], ['3']);
  assert.deepEqual(frozenFields, ['3', '2', '4']);
  assert.deepEqual(getResultColumnDisplayOrder(['1', '2', '3', '4'], frozenFields), ['3', '2', '4', '1']);
});

test('unfreezing restores the base order captured before freezing', () => {
  assert.deepEqual(getResultColumnDisplayOrder(['1', '2', '3', '4'], ['3', '2']), ['3', '2', '1', '4']);
  assert.deepEqual(getResultColumnDisplayOrder(['1', '2', '3', '4'], []), ['1', '2', '3', '4']);
});

test('dragging scrollable columns while frozen updates only their base-order slots', () => {
  assert.deepEqual(
    mergeResultColumnOrderFromDisplay(['1', '2', '3', '4'], ['2', '4', '3', '1'], ['2', '4']),
    ['3', '2', '1', '4'],
  );
  assert.deepEqual(
    mergeResultColumnOrderFromDisplay(['1', '2', '3', '4'], ['4', '2', '1', '3'], []),
    ['4', '2', '1', '3'],
  );
});

test('display-order merges keep hidden or otherwise omitted fields in their base slots', () => {
  assert.deepEqual(
    mergeResultColumnOrderFromDisplay(['1', '2', '3', '4'], ['4', '2', '1'], ['2']),
    ['4', '2', '3', '1'],
  );
});

test('freeze count includes row numbers and refuses freezing every visible data column', () => {
  const renderedColumns = orderResultColumns(resultColumns, ['2', '4', '1', '3']);
  assert.equal(getResultFrozenColumnCount(renderedColumns, ['2', '4']), 3);
  assert.equal(canFreezeResultColumns(renderedColumns, ['2', '4'], ['1', '3']), false);
  assert.equal(canFreezeResultColumns(renderedColumns, ['2'], ['4']), true);
});

test('cell metadata lookup resolves the stable field after an earlier column is hidden', () => {
  const record = {
    __CHAT2DB_CELL_META__: [undefined, { value: 'first' }, { value: 'second' }],
  } as any;
  const table = {
    getHeaderField: (col: number) => (col === 1 ? '2' : undefined),
  } as any;
  assert.equal(getResultCellMetaAtTableColumn(table, record, 1, 1)?.value, 'second');
});

test('clearing a selection never restores an active cell from a pending frame', () => {
  assert.equal(resolveResultSelectionActiveCell([], { col: 2, row: 3 }), undefined);
  assert.deepEqual(
    resolveResultSelectionActiveCell(
      [
        { col: 1, row: 1 },
        { col: 2, row: 1 },
      ],
      { col: 4, row: 2 },
    ),
    { col: 2, row: 1 },
  );
});

test('result context menu groups use one divider between populated action categories', () => {
  assert.deepEqual(joinContextMenuGroups(['view'], [], ['copy', 'paste'], ['delete']), [
    'view',
    { type: 'divider' },
    'copy',
    'paste',
    { type: 'divider' },
    'delete',
  ]);
});
