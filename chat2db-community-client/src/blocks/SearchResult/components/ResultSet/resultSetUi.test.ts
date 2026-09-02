import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
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
  getResultColumnNameAtTableColumn,
  getResultColumnDisplayOrder,
  getResultFrozenColumnCount,
  hideResultColumnFields,
  mergeResultColumnOrderFromDisplay,
  orderResultColumns,
  updateHiddenResultColumnFields,
} from '../ResultSetTable/columnState';
import {
  resolveResultInspectorActiveCell,
  resolveResultSelectionActiveCell,
} from '../ResultSetTable/selectionState';
import { areResultCellValuesEquivalent } from './inspectorState';
import {
  isResultHeaderContext,
  joinContextMenuGroups,
} from '../ResultSetTable/event/onContextmenuCell/menuGroups';
import { matchResultSearchValue } from '../FESearch/searchMatcher';
import {
  hasActiveResultEditorChange,
  hasPendingResultEdit,
  resolvePendingResultEditOperations,
} from './resultEditActions';

const exportBarSource = readFileSync('src/blocks/SearchResult/components/ExportBar/index.tsx', 'utf8');
const tabsSource = readFileSync('src/components/Tabs/index.tsx', 'utf8');
const dropdownTriggerSource = readFileSync('src/components/DropdownChevronTrigger/index.tsx', 'utf8');
const dropdownTriggerStyleSource = readFileSync('src/components/DropdownChevronTrigger/style.ts', 'utf8');
const resultToolbarStyleSource = readFileSync(
  'src/blocks/SearchResult/components/ResultSetToolbar/style.ts',
  'utf8',
);
const resultToolbarSource = readFileSync(
  'src/blocks/SearchResult/components/ResultSetToolbar/index.tsx',
  'utf8',
);
const resultSetTableComponentSource = readFileSync(
  'src/blocks/SearchResult/components/ResultSetTable/index.tsx',
  'utf8',
);
const columnVisibilityModalSource = readFileSync(
  'src/blocks/SearchResult/components/ResultSetTable/ColumnVisibilityModal.tsx',
  'utf8',
);
const resultTableStyleSource = readFileSync(
  'src/blocks/SearchResult/components/ResultSetTable/style.ts',
  'utf8',
);
const rowDetailSource = readFileSync(
  'src/blocks/SearchResult/components/RowDetail/index.tsx',
  'utf8',
);
const resultSetSource = readFileSync(
  'src/blocks/SearchResult/components/ResultSet/index.tsx',
  'utf8',
);
const sqlExecuteSource = readFileSync(
  'src/pages/main/workspace/components/SQLExecute/index.tsx',
  'utf8',
);
const headerTooltipSource = readFileSync(
  'src/blocks/SearchResult/components/ResultSetTable/hooks/useHeaderTooltip.tsx',
  'utf8',
);
const resultColumnDataTreatingSource = readFileSync(
  'src/blocks/SearchResult/components/ResultSetTable/utils/dataTreating.ts',
  'utf8',
);

test('more-tabs and export chevrons share one aligned trailing slot', () => {
  assert.match(exportBarSource, /<DropdownChevronTrigger>\{i18n\('common\.text\.export'\)\}/);
  assert.match(tabsSource, /<DropdownChevronTrigger aria-label=\{i18n\('common\.text\.moreTabs'\)\} \/>/);
  assert.match(dropdownTriggerSource, /className=\{styles\.chevronSlot\}/);
  assert.match(
    dropdownTriggerStyleSource,
    /chevronSlot:[\s\S]*?width: 29px;[\s\S]*?align-items: center;[\s\S]*?justify-content: center;/,
  );
  assert.match(resultToolbarStyleSource, /toolBar:[\s\S]*?padding: 0;/);
  assert.match(resultToolbarStyleSource, /toolBarRight:[\s\S]*?height: 100%;[\s\S]*?align-items: center;/);
});

test('query result export sends the concrete table as the task target', () => {
  assert.match(exportBarSource, /const tableName = resultData\.tableName \|\| params\.tableName;/);
  assert.match(exportBarSource, /tableNames: tableName \? \[tableName\] : undefined,/);
});

test('result edit actions commit the active editor before reading pending operations', async () => {
  const calls: string[] = [];
  let operations: Array<{ type: string }> = [];
  const result = await resolvePendingResultEditOperations({
    completeActiveEditor: async () => {
      calls.push('complete');
      await Promise.resolve();
      operations = [{ type: 'UPDATE' }];
    },
    getOperations: () => {
      calls.push('read');
      return operations;
    },
  });

  assert.deepEqual(calls, ['complete', 'read']);
  assert.deepEqual(result, [{ type: 'UPDATE' }]);
  assert.deepEqual(
    await resolvePendingResultEditOperations({
      completeActiveEditor: async () => undefined,
      getOperations: () => undefined,
    }),
    [],
    'an unchanged edit cannot produce an empty preview or submit request',
  );
  assert.equal(hasPendingResultEdit(false, false), false);
  assert.equal(hasPendingResultEdit(true, false), true);
  assert.equal(hasPendingResultEdit(false, true), true);
  const editorState = {
    editorManager: {
      editingEditor: { getValue: () => 'original' },
      editCell: { col: 2, row: 1 },
    },
    getCellOriginValue: () => 'original',
  };
  assert.equal(hasActiveResultEditorChange(editorState), false);
  editorState.editorManager.editingEditor.getValue = () => 'changed';
  assert.equal(hasActiveResultEditorChange(editorState), true);
  editorState.editorManager.editingEditor.getValue = () => 'original';
  assert.equal(hasActiveResultEditorChange(editorState), false);
  assert.match(resultToolbarSource, /disabled=\{!hasPendingChanges\}/);
  assert.doesNotMatch(resultToolbarSource, /isActive=\{hasPendingChanges\}/);
  assert.doesNotMatch(resultToolbarSource, /type="primary"/);
  assert.equal(
    resultToolbarSource.match(/hasPendingChanges && styles\.pendingAction/g)?.length,
    3,
    'revert, preview, and submit should highlight only their icons when changes are pending',
  );
  assert.match(resultSetTableComponentSource, /tableElement\.addEventListener\('input', syncActiveEditState, true\)/);
  assert.doesNotMatch(resultSetTableComponentSource, /document\.addEventListener\('(input|change)'/);
  assert.match(
    resultSetSource,
    /const handleRevocation = useCallback\(async \(\) => \{[\s\S]*?await completeActiveEditor\(\);[\s\S]*?operationRecordUtils\?\.handleRevocation\(\)/,
  );
  assert.match(
    resultSetSource,
    /const operations = await getPendingEditOperations\(\);[\s\S]*?if \(!operations\.length\) \{[\s\S]*?return;/,
  );
  assert.match(resultSetSource, /data-shortcut-scope=\{ShortcutScope\.ResultSet\}/);
});

test('result table sorting and paging refresh the current result instead of creating a console result', () => {
  assert.doesNotMatch(sqlExecuteSource, /onResultPagingChange=\{handleResultPagingChange\}/);
  assert.match(
    resultSetSource,
    /const requestSequence = \+\+executeRequestSequenceRef\.current;[\s\S]*?executeSQL\(executeSqlParams\)[\s\S]*?setResultData\(/,
  );
});

test('manage-columns title exposes an aligned localized help tooltip', () => {
  assert.match(columnVisibilityModalSource, /<CircleHelp aria-hidden="true" size=\{14\} \/>/);
  assert.match(columnVisibilityModalSource, /i18n\('common\.text\.manageColumns\.tooltip'\)/);
  assert.match(columnVisibilityModalSource, /aria-label=\{i18n\('common\.text\.manageColumns\.tooltip'\)\}/);
  assert.match(
    resultTableStyleSource,
    /columnVisibilityTitle:[\s\S]*?display: inline-flex;[\s\S]*?align-items: center;/,
  );
});

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

test('result search matches custom-rendered field names and regular cell values', () => {
  const table = {
    columns: [{ field: '1', originalData: { name: 'record_primary_key' } }],
    getHeaderField: (col: number) => (col === 1 ? '1' : undefined),
    isHeader: (_col: number, row: number) => row === 0,
  } as any;

  assert.equal(matchResultSearchValue('primary', '', { col: 1, row: 0, table }), true);
  assert.equal(matchResultSearchValue('missing', '', { col: 1, row: 0, table }), false);
  assert.equal(matchResultSearchValue('customer', 'customer-001', { col: 1, row: 1, table }), true);
});

test('record field focus updates the cell used by the value inspector', () => {
  assert.match(rowDetailSource, /onFocus=\{\(\) => handleFieldActivate\(item\)\}/);
  assert.match(rowDetailSource, /onActiveFieldChange\?\.\(\{[\s\S]*?field: item\.field,[\s\S]*?\}\);/);
  assert.match(resultSetSource, /onActiveFieldChange=\{handleRowDetailActiveFieldChange\}/);
  assert.match(resultSetSource, /const lastActiveCell = lastActiveCellRef\.current;/);
});

test('unchanged typed values do not produce a row-detail cell update', () => {
  assert.equal(areResultCellValuesEquivalent(42, '42'), true);
  assert.equal(areResultCellValuesEquivalent(true, 'true'), true);
  assert.equal(areResultCellValuesEquivalent(false, 'false'), true);
  assert.equal(areResultCellValuesEquivalent(null, null), true);
  assert.equal(areResultCellValuesEquivalent(undefined, null), true);
  assert.equal(areResultCellValuesEquivalent(42, '43'), false);
  assert.equal(areResultCellValuesEquivalent(null, ''), false);
});

test('value refresh preserves the inspector field while real table selection replaces it', () => {
  const inspectorField = { row: 1, field: '2' };
  const oldTableField = { row: 1, field: '1' };
  const newTableField = { row: 2, field: '3' };

  assert.equal(
    resolveResultInspectorActiveCell(inspectorField, oldTableField, 'value-change'),
    inspectorField,
  );
  assert.equal(
    resolveResultInspectorActiveCell(inspectorField, newTableField, 'table-selection'),
    newTableField,
  );
  assert.equal(
    resolveResultInspectorActiveCell(inspectorField, oldTableField, 'table-selection', true),
    inspectorField,
  );
  assert.equal(
    resolveResultInspectorActiveCell(undefined, newTableField, 'value-change'),
    newTableField,
  );
});

test('result table advances inspector interaction revisions for pointer and keyboard input', () => {
  const resultSetTableSource = readFileSync(
    'src/blocks/SearchResult/components/ResultSetTable/index.tsx',
    'utf8',
  );
  assert.match(resultSetTableSource, /onPointerDown=\{handleTablePointerDown\}/);
  assert.match(resultSetTableSource, /onKeyDown=\{handleTableKeyDown\}/);
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

test('result headers render available metadata values as lines without labels', () => {
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

test('resized result headers use the live cell width without expanding their initial recommendation', () => {
  const theme = {
    colorText: '#111111',
    colorPrimary: '#1677ff',
    colorTextSecondary: '#888888',
    fontFamily: 'Inter',
  } as any;
  const longName = 'a'.repeat(800);
  const initialRender = createResultHeaderCustomRender({
    data: { dataType: 'STRING' as any, name: longName },
    fontSize: 13,
    theme,
  });
  const resizedRender = createResultHeaderCustomRender({
    data: { dataType: 'STRING' as any, name: longName },
    fontSize: 13,
    theme,
    availableWidth: 720,
  });

  assert.equal(initialRender.expectedWidth, 360);
  assert.equal(initialRender.elements[0].maxLineWidth, 302);
  assert.equal(resizedRender.expectedWidth, 360);
  assert.equal(resizedRender.elements[0].maxLineWidth, 662);
  assert.match(resultColumnDataTreatingSource, /availableWidth: args\.rect\?\.width/);
});

test('result header tooltip preserves complete metadata and wraps long identifiers', () => {
  assert.match(headerTooltipSource, /getHeaderMetadataRows\(originalData\)/);
  assert.match(headerTooltipSource, /\{item\.value\}/);
  assert.match(headerTooltipSource, /overflow-wrap: anywhere;/);
  assert.doesNotMatch(headerTooltipSource, /text-overflow: ellipsis;/);
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

test('pinned result already moved into incoming history is not retained in current results', () => {
  const pinnedResult = { uuid: 'pinned', value: 'pinned result' };
  const nextResult = { uuid: 'next', value: 'new result' };

  assert.deepEqual(
    retainPinnedResults([nextResult], [pinnedResult], new Set(['pinned']), [pinnedResult]),
    [nextResult],
  );
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

test('column-name lookup copies original metadata after columns are reordered', () => {
  const table = {
    columns: [
      { field: '2', originalData: { name: 'second_column' } },
      { field: '1', originalData: { name: 'first_column' } },
    ],
    getHeaderField: (col: number) => (col === 1 ? '2' : '1'),
  } as any;

  assert.equal(getResultColumnNameAtTableColumn(table, 1), 'second_column');
  assert.equal(getResultColumnNameAtTableColumn(table, 2), 'first_column');
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
