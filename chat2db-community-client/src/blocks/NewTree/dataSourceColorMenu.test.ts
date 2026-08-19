import assert from 'node:assert/strict';
import { OperationColumn, TreeNodeType } from '@/constants/tree';
import {
  DATA_SOURCE_COLOR_PRESETS,
  getDataSourceColorSelectionIndex,
  getNextDataSourceColorControlIndex,
  resolveDataSourceColorSelection,
  withDataSourceColorMenuOption,
} from './dataSourceColorMenu';

assert.deepEqual(resolveDataSourceColorSelection(null), { type: 'clear', color: null });
assert.deepEqual(resolveDataSourceColorSelection('#f5222d'), { type: 'preset', color: '#F5222D' });
assert.deepEqual(resolveDataSourceColorSelection('#123456'), { type: 'custom', color: '#123456' });
assert.equal(DATA_SOURCE_COLOR_PRESETS.length, 8);
assert.equal(getDataSourceColorSelectionIndex(null), 0);
assert.equal(getDataSourceColorSelectionIndex('#F5222D'), 1);
assert.equal(getDataSourceColorSelectionIndex('#123456'), DATA_SOURCE_COLOR_PRESETS.length + 1);
assert.equal(getNextDataSourceColorControlIndex(0, 'ArrowLeft'), DATA_SOURCE_COLOR_PRESETS.length + 1);
assert.equal(getNextDataSourceColorControlIndex(DATA_SOURCE_COLOR_PRESETS.length + 1, 'ArrowRight'), 0);
assert.equal(getNextDataSourceColorControlIndex(4, 'Home'), 0);
assert.equal(getNextDataSourceColorControlIndex(4, 'End'), DATA_SOURCE_COLOR_PRESETS.length + 1);

const sourceOptions = withDataSourceColorMenuOption(
  [
    OperationColumn.EditSource,
    OperationColumn.ClearDataSourceColor,
    OperationColumn.SetDataSourceColor,
    OperationColumn.RemoveDataSource,
  ],
  TreeNodeType.DATA_SOURCE,
);
assert.deepEqual(sourceOptions, [
  OperationColumn.EditSource,
  OperationColumn.SetDataSourceColor,
  OperationColumn.Divider,
  OperationColumn.RemoveDataSource,
]);
assert.equal(
  sourceOptions.filter((option) => option === OperationColumn.SetDataSourceColor).length,
  1,
  'the data source menu contains one inline color palette row',
);
assert.equal(sourceOptions.includes(OperationColumn.ClearDataSourceColor), false, 'the legacy clear item is removed');
assert.equal(
  sourceOptions[sourceOptions.indexOf(OperationColumn.SetDataSourceColor) + 1],
  OperationColumn.Divider,
  'the inline color row is separated from copy and management actions',
);

const tableOptions = [OperationColumn.OpenTable, OperationColumn.EditTable];
assert.equal(
  withDataSourceColorMenuOption(tableOptions, TreeNodeType.TABLE),
  tableOptions,
  'non-data-source menus are unchanged',
);

console.log('Data source color menu tests passed');
