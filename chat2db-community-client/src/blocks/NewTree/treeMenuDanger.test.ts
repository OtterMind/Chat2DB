import assert from 'node:assert/strict';
import { OperationColumn } from '@/constants/tree';
import { isDangerousTreeOperation } from './treeMenuDanger';

const dangerousOperations = [
  OperationColumn.RemoveGroup,
  OperationColumn.RemoveAiDataCollection,
  OperationColumn.RemoveAiDataCollectionElement,
  OperationColumn.DeleteTreeNode,
  OperationColumn.RemoveDataSource,
  OperationColumn.DeleteTable,
  OperationColumn.DeleteSchema,
  OperationColumn.DeleteDatabase,
  OperationColumn.RemoveConsole,
  OperationColumn.TruncateTable,
];

dangerousOperations.forEach((operation) => {
  assert.equal(isDangerousTreeOperation(operation), true, `${operation} is rendered as a dangerous operation`);
});

[
  OperationColumn.EditSource,
  OperationColumn.CopyDataSource,
  OperationColumn.Refresh,
  OperationColumn.Rename,
].forEach((operation) => {
  assert.equal(isDangerousTreeOperation(operation), false, `${operation} keeps the normal menu style`);
});

console.log('Tree menu danger tests passed');
