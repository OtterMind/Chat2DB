import assert from 'node:assert/strict';
import onChangeCellValue from './index';

let listener: ((event: any) => void) | undefined;
let restoreCount = 0;
let trackedChangeCount = 0;
let largeValue = true;
let hiddenLeadingColumnLargeValue = false;
let headerField = '1';
let failRestore = false;

const tableInstance = {
  on: (_event: string, callback: (event: any) => void) => {
    listener = callback;
    return 1;
  },
  getRecordByCell: () => ({
    CHAT2DB_ROW_NUMBER: 'row-1',
    __CHAT2DB_CELL_META__: {
      1: { largeValue },
      2: { largeValue: hiddenLeadingColumnLargeValue },
    },
  }),
  getHeaderField: () => headerField,
  changeCellValue: (col: number, row: number) => {
    restoreCount += 1;
    if (failRestore) {
      throw new Error('restore failed');
    }
    assert.ok(listener);
    listener({ col, row, currentValue: 'attempted edit', changedValue: 'original preview' });
  },
};

onChangeCellValue(tableInstance as any, () => {
  trackedChangeCount += 1;
});

assert.ok(listener);
listener({ col: 1, row: 1, currentValue: 'original preview', changedValue: 'attempted edit' });
assert.equal(restoreCount, 1, 'the synthetic VTable event must not recursively restore the cell again');
assert.equal(trackedChangeCount, 0, 'large-value preview restores must not be tracked as user edits');

failRestore = true;
assert.throws(
  () => listener?.({ col: 1, row: 1, currentValue: 'original preview', changedValue: 'attempted edit' }),
  /restore failed/,
  'restore failures should remain observable',
);
failRestore = false;
largeValue = false;
listener({ col: 1, row: 1, currentValue: 'before', changedValue: 'after' });
assert.equal(trackedChangeCount, 1, 'the guard should reset after a failure so ordinary edits are still tracked');

headerField = '2';
hiddenLeadingColumnLargeValue = true;
const restoreCountBeforeHiddenColumnEdit = restoreCount;
listener({ col: 1, row: 1, currentValue: 'original preview', changedValue: 'attempted edit' });
assert.equal(
  restoreCount,
  restoreCountBeforeHiddenColumnEdit + 1,
  'a screen column must resolve large-value metadata by its stable field after a leading column is hidden',
);
assert.equal(trackedChangeCount, 1, 'restoring a remapped large-value preview must not be tracked as a user edit');

headerField = '1';
hiddenLeadingColumnLargeValue = false;
onChangeCellValue(
  tableInstance as any,
  () => {
    trackedChangeCount += 1;
  },
  new Set(['1']),
);
const restoreCountBeforeFrozenColumnEdit = restoreCount;
listener?.({ col: 1, row: 1, currentValue: 'before', changedValue: 'after' });
assert.equal(restoreCount, restoreCountBeforeFrozenColumnEdit + 1, 'a frozen field edit must restore its original value');
assert.equal(trackedChangeCount, 1, 'a frozen field edit must not be tracked as a user edit');

console.log('onChangeCellValue tests passed');
