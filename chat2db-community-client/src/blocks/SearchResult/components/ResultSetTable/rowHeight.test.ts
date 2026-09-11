import assert from 'node:assert/strict';
import test from 'node:test';
import type { ITableInstance } from '@/blocks/CanvasTable/typings';
import {
  getCollapsedResultCellPreview,
  isResultTableRowExpanded,
  resetResultTableLayout,
  updateResultTableRowExpansion,
  RESULT_TABLE_CELL_PREVIEW_CHARACTERS,
} from './rowHeight';

function tableFixture(overrides: Partial<ITableInstance> = {}) {
  const heights = new Map<number, number>([
    [0, 32],
    [1, 28],
    [2, 120],
    [3, 28],
  ]);
  const refreshed: Array<[number, number]> = [];
  let scheduledFrames = 0;
  let clearedSelections = 0;
  let clearedRowHeightCaches = 0;
  const table = {
    colCount: 4,
    rowCount: 4,
    columnHeaderLevelCount: 1,
    bottomFrozenRowCount: 0,
    getDefaultRowHeight: () => 28,
    getRowHeight: (row: number) => heights.get(row) || 28,
    internalProps: { _heightResizedRowMap: new Set([2]) },
    clearSelected: () => {
      clearedSelections += 1;
    },
    clearRowHeightCache: () => {
      clearedRowHeightCaches += 1;
    },
    scenegraph: {
      updateCellContent: (col: number, row: number) => refreshed.push([col, row]),
      updateNextFrame: () => {
        scheduledFrames += 1;
      },
    },
    ...overrides,
  } as unknown as ITableInstance;
  return {
    table,
    heights,
    refreshed,
    scheduledFrames: () => scheduledFrames,
    clearedSelections: () => clearedSelections,
    clearedRowHeightCaches: () => clearedRowHeightCaches,
  };
}

test('keeps short single-line values on the default renderer', () => {
  assert.equal(getCollapsedResultCellPreview('short value', false), undefined);
});

test('builds a bounded single-line preview for collapsed long and multiline values', () => {
  assert.equal(
    getCollapsedResultCellPreview('x'.repeat(RESULT_TABLE_CELL_PREVIEW_CHARACTERS + 20), false),
    `${'x'.repeat(RESULT_TABLE_CELL_PREVIEW_CHARACTERS)}...`,
  );
  assert.equal(getCollapsedResultCellPreview('first\r\nsecond', false), 'first second...');
});

test('does not expand a row while VTable is still resizing it', () => {
  const { table } = tableFixture();

  assert.equal(table.internalProps._heightResizedRowMap.has(2), true);
  assert.equal(isResultTableRowExpanded(table, 2), false);
});

test('expands only after resize ends and collapses again at the default height', () => {
  const { table, heights, refreshed, scheduledFrames } = tableFixture();

  assert.equal(updateResultTableRowExpansion(table, 2, 120), 3);
  assert.equal(isResultTableRowExpanded(table, 2), true);
  assert.equal(getCollapsedResultCellPreview('first\nsecond', true), undefined);
  assert.deepEqual(refreshed, [
    [1, 2],
    [2, 2],
    [3, 2],
  ]);
  assert.equal(scheduledFrames(), 1);

  heights.set(2, 28);
  assert.equal(updateResultTableRowExpansion(table, 2, 28), 3);
  assert.equal(isResultTableRowExpanded(table, 2), false);
  assert.equal(scheduledFrames(), 2);
});

test('drops stale expansion state when VTable resets a row height', () => {
  const { table, heights } = tableFixture();

  updateResultTableRowExpansion(table, 2, 120);
  heights.set(2, 28);

  assert.equal(isResultTableRowExpanded(table, 2), false);
});

test('resets row layout and selection before records are replaced', () => {
  const { table, clearedSelections, clearedRowHeightCaches } = tableFixture();

  updateResultTableRowExpansion(table, 2, 120);
  resetResultTableLayout(table);

  assert.equal(isResultTableRowExpanded(table, 2), false);
  assert.equal(table.internalProps._heightResizedRowMap.size, 0);
  assert.equal(clearedSelections(), 1);
  assert.equal(clearedRowHeightCaches(), 1);
});
