import assert from 'node:assert/strict';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import useOperationRecord, { OperationRecordUtils } from './useOperationRecord';

const createChangeRecord = (field: string, currentValue: string, changedValue: string, rowId = 'row-1') => ({
  field,
  rowId,
  rawValue: currentValue,
  currentValue,
  changedValue,
});

const renderOperationRecord = (tableInstance: any) => {
  let operationRecordUtils: OperationRecordUtils | undefined;

  const HookHarness = () => {
    operationRecordUtils = useOperationRecord({
      tableInstance,
      theme: {} as any,
    }).operationRecordUtils;
    return null;
  };

  renderToStaticMarkup(createElement(HookHarness));
  assert.ok(operationRecordUtils);
  return operationRecordUtils;
};

function testNoSelectionRevertKeepsTrackingEnabled() {
  const row = {
    CHAT2DB_ROW_NUMBER: 'row-1',
    1: 'before',
  };
  const operationRecordUtils = renderOperationRecord({
    columns: [{ field: '1' }],
    records: [row],
    dataSource: { currentIndexedData: [0] },
    getSelectedCellInfos: () => [],
    clearSelected: () => undefined,
  });

  operationRecordUtils.handleRevocation();
  operationRecordUtils.handleCellValueChange(createChangeRecord('1', 'before', 'after'));

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
}

function testMultiCellRevertDoesNotRecordRestoreCallbacks() {
  const row = {
    CHAT2DB_ROW_NUMBER: 'row-1',
    1: 'before-1',
    2: 'before-2',
  };
  const operationRecordUtilsRef: { current?: OperationRecordUtils } = {};
  let restoreCallbackCount = 0;
  const tableInstance = {
    columns: [{ field: '1' }, { field: '2' }],
    records: [row],
    dataSource: { currentIndexedData: [0] },
    getSelectedCellInfos: () => [
      [
        { field: '1', originData: row },
        { field: '2', originData: row },
      ],
    ],
    arrangeCustomCellStyle: () => undefined,
    changeCellValue: (col: number, _row: number, value: string) => {
      restoreCallbackCount += 1;
      const field = String(col);
      assert.ok(operationRecordUtilsRef.current);
      operationRecordUtilsRef.current.handleCellValueChange(createChangeRecord(field, `after-${field}`, value));
    },
    clearSelected: () => undefined,
  };
  const operationRecordUtils = renderOperationRecord(tableInstance);
  operationRecordUtilsRef.current = operationRecordUtils;
  operationRecordUtils.handleCellValueChange(createChangeRecord('1', 'before-1', 'after-1'));
  operationRecordUtils.handleCellValueChange(createChangeRecord('2', 'before-2', 'after-2'));

  operationRecordUtils.handleRevocation();

  assert.equal(restoreCallbackCount, 2);
  assert.deepEqual(operationRecordUtils.getOperationChangeDetail(), []);
}

function testRevertFailureRestoresTracking() {
  const row = {
    CHAT2DB_ROW_NUMBER: 'row-1',
    1: 'before-1',
    2: 'before-2',
  };
  let shouldThrow = true;
  const tableInstance = {
    columns: [{ field: '1' }, { field: '2' }],
    records: [row],
    dataSource: { currentIndexedData: [0] },
    getSelectedCellInfos: () => [[{ field: '1', originData: row }]],
    arrangeCustomCellStyle: () => undefined,
    changeCellValue: () => {
      if (shouldThrow) {
        throw new Error('restore failed');
      }
    },
    clearSelected: () => undefined,
  };
  const operationRecordUtils = renderOperationRecord(tableInstance);
  operationRecordUtils.handleCellValueChange(createChangeRecord('1', 'before-1', 'after-1'));

  assert.throws(() => operationRecordUtils.handleRevocation(), /restore failed/);
  shouldThrow = false;
  operationRecordUtils.handleCellValueChange(createChangeRecord('2', 'before-2', 'after-2'));

  const operations = operationRecordUtils.getOperationChangeDetail();
  assert.equal(operations.length, 1);
  assert.equal(operations[0].dataList[2], 'after-2');
}

function testNestedPauseStaysActiveUntilRevertCompletes() {
  const row = {
    CHAT2DB_ROW_NUMBER: 'row-1',
    1: 'before-1',
    2: 'before-2',
  };
  const newRow = {
    CHAT2DB_ROW_NUMBER: 'new-row',
    1: null,
    2: null,
  };
  const records: any[] = [row];
  const dataSource = { currentIndexedData: [0] };
  const operationRecordUtilsRef: { current?: OperationRecordUtils } = {};
  const tableInstance = {
    columns: [{ field: '1' }, { field: '2' }],
    records,
    dataSource,
    getSelectedCellInfos: () => [[{ field: '1', originData: row }], [{ field: '1', originData: newRow }]],
    arrangeCustomCellStyle: () => undefined,
    changeCellValue: () => undefined,
    deleteRecords: () => {
      assert.ok(operationRecordUtilsRef.current);
      operationRecordUtilsRef.current.handleCellValueChange(createChangeRecord('2', 'before-2', 'unexpected'));
    },
    addRecords: (newRecords: any[]) => {
      records.push(...newRecords);
      dataSource.currentIndexedData = records.map((_, index) => index);
    },
    scrollToCell: () => undefined,
    clearSelected: () => undefined,
  };
  const operationRecordUtils = renderOperationRecord(tableInstance);
  operationRecordUtilsRef.current = operationRecordUtils;
  operationRecordUtils.handleCellValueChange(createChangeRecord('1', 'before-1', 'after-1'));
  operationRecordUtils.handleAddBlankRow(newRow, 'new-row');

  operationRecordUtils.handleRevocation();

  assert.deepEqual(operationRecordUtils.getOperationChangeDetail(), []);
}

function testFailedCreateRowRevertKeepsCreateRecord() {
  const newRow = {
    CHAT2DB_ROW_NUMBER: 'new-row',
    1: null,
  };
  const records: any[] = [];
  const dataSource = { currentIndexedData: [] as number[] };
  const tableInstance = {
    columns: [{ field: '1' }],
    records,
    dataSource,
    getSelectedCellInfos: () => [[{ field: '1', originData: newRow }]],
    arrangeCustomCellStyle: () => undefined,
    deleteRecords: () => {
      throw new Error('delete failed');
    },
    addRecords: (newRecords: any[]) => {
      records.push(...newRecords);
      dataSource.currentIndexedData = records.map((_, index) => index);
    },
    scrollToCell: () => undefined,
    clearSelected: () => undefined,
  };
  const operationRecordUtils = renderOperationRecord(tableInstance);
  operationRecordUtils.handleAddBlankRow(newRow, 'new-row');

  assert.throws(() => operationRecordUtils.handleRevocation(), /delete failed/);
  assert.equal(operationRecordUtils.isCreateRow('new-row'), true);
}

function testDeleteFailureAfterPhysicalRemovalKeepsBookkeepingConsistent() {
  const row = {
    CHAT2DB_ROW_NUMBER: 'row-1',
    1: 'before-1',
  };
  const newRow = {
    CHAT2DB_ROW_NUMBER: 'new-row',
    1: null,
  };
  const records: any[] = [row];
  const dataSource = { currentIndexedData: [0] };
  const tableInstance = {
    columns: [{ field: '1' }],
    records,
    dataSource,
    getSelectedCellInfos: () => [
      [{ field: '1', originData: newRow, row: 2 }],
      [{ field: '1', originData: row, row: 1 }],
    ],
    arrangeCustomCellStyle: () => undefined,
    changeCellValue: () => {
      throw new Error('restore failed');
    },
    deleteRecords: () => undefined,
    addRecords: (newRecords: any[]) => {
      records.push(...newRecords);
      dataSource.currentIndexedData = records.map((_, index) => index);
    },
    scrollToCell: () => undefined,
    clearSelected: () => undefined,
  };
  const operationRecordUtils = renderOperationRecord(tableInstance);
  operationRecordUtils.handleAddBlankRow(newRow, 'new-row');
  operationRecordUtils.handleCellValueChange(createChangeRecord('1', 'before-1', 'after-1'));

  assert.throws(() => operationRecordUtils.handleDeleteRow(), /restore failed/);
  assert.equal(operationRecordUtils.isCreateRow('new-row'), false);
  assert.deepEqual(
    operationRecordUtils.getOperationChangeDetail().map((operation) => operation.type),
    ['DELETE'],
  );
}

testNoSelectionRevertKeepsTrackingEnabled();
testMultiCellRevertDoesNotRecordRestoreCallbacks();
testRevertFailureRestoresTracking();
testNestedPauseStaysActiveUntilRevertCompletes();
testFailedCreateRowRevertKeepsCreateRecord();
testDeleteFailureAfterPhysicalRemovalKeepsBookkeepingConsistent();

console.log('useOperationRecord tests passed');
