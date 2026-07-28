import assert from 'node:assert/strict';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import useOperationRecord, { OperationRecordUtils } from './useOperationRecord';

const row = {
  CHAT2DB_ROW_NUMBER: 'row-1',
  1: 'before',
};

const tableInstance = {
  columns: [{ field: '1' }],
  records: [row],
  dataSource: { currentIndexedData: [0] },
  getSelectedCellInfos: () => [],
  clearSelected: () => undefined,
};

let operationRecordUtils: OperationRecordUtils | undefined;

const HookHarness = () => {
  operationRecordUtils = useOperationRecord({
    tableInstance: tableInstance as any,
    theme: {} as any,
  }).operationRecordUtils;
  return null;
};

renderToStaticMarkup(createElement(HookHarness));
assert.ok(operationRecordUtils);

operationRecordUtils.handleRevocation();
operationRecordUtils.handleCellValueChange({
  field: '1',
  rowId: 'row-1',
  rawValue: 'before',
  currentValue: 'before',
  changedValue: 'after',
});

assert.deepEqual(operationRecordUtils.getOperationChangeDetail(), [
  {
    rowId: 'row-1',
    type: 'UPDATE',
    dataList: {
      ...row,
      1: 'after',
    },
    oldDataList: row,
  },
]);

console.log('useOperationRecord tests passed');
