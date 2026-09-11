import { DatabaseCapability } from '@/constants/databaseCapabilities';
import { TreeNodeType } from '@/constants/tree';

export const MONITOR_TREE_ITEMS = [
  {
    capability: DatabaseCapability.ACTIVE_TRANSACTION_INSPECTION,
    treeNodeType: TreeNodeType.ACTIVE_TRANSACTIONS,
    titleKey: 'workspace.ops.activeTransactions',
  },
] as const;

export const createMonitorTreeNodeKey = (dataSourceId?: number) => `dataSource_${dataSourceId}-monitor`;

export const createActiveTransactionsTreeNodeKey = (dataSourceId?: number) =>
  `${createMonitorTreeNodeKey(dataSourceId)}-activeTransactions`;

export const createActiveTransactionsWorkspaceTabId = (dataSourceId?: number) =>
  `activeTransactions-${dataSourceId}`;
