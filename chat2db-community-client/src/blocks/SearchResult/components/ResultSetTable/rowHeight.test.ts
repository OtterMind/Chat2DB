import assert from 'node:assert/strict';
import test from 'node:test';
import type { ITableInstance } from '@/blocks/CanvasTable/typings';
import { capResultTableAutoRowHeights } from './rowHeight';

function tableFixture(overrides: Partial<ITableInstance> = {}) {
  const heights = new Map<number, number>([
    [0, 32],
    [1, 520],
    [2, 360],
    [3, 28],
  ]);
  const resizedRows = new Set([2]);
  const resized: Array<[number, number]> = [];
  const table = {
    heightMode: 'autoHeight',
    rowCount: 4,
    columnHeaderLevelCount: 1,
    bottomFrozenRowCount: 0,
    getRowHeight: (row: number) => heights.get(row) || 0,
    internalProps: { _heightResizedRowMap: resizedRows },
    scenegraph: {
      setRowHeight: (row: number, height: number) => {
        heights.set(row, height);
        resized.push([row, height]);
      },
    },
    ...overrides,
  } as unknown as ITableInstance;
  return { table, heights, resized };
}

test('caps automatic rows while preserving manually resized rows', () => {
  const { table, heights, resized } = tableFixture();

  assert.equal(capResultTableAutoRowHeights(table, 240), 1);
  assert.equal(heights.get(1), 240);
  assert.equal(heights.get(2), 360);
  assert.deepEqual(resized, [[1, 240]]);
});

test('does not cap rows when the table is not in auto-height mode', () => {
  const { table, heights, resized } = tableFixture({ heightMode: 'standard' } as Partial<ITableInstance>);

  assert.equal(capResultTableAutoRowHeights(table, 240), 0);
  assert.equal(heights.get(1), 520);
  assert.deepEqual(resized, []);
});
