import { OperationColumn } from '@/constants/tree';

const DANGEROUS_TREE_OPERATIONS = new Set<OperationColumn>([
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
]);

export function isDangerousTreeOperation(operation: OperationColumn): boolean {
  return DANGEROUS_TREE_OPERATIONS.has(operation);
}
