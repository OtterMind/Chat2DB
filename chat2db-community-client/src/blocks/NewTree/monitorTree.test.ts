import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseTypeCode } from '@/constants/common';
import { DatabaseCapability } from '@/constants/databaseCapabilities';
import { TreeNodeType } from '@/constants/tree';
import { isDatabaseCapabilitySupported } from '@/utils/databaseJudgments';
import {
  createActiveTransactionsTreeNodeKey,
  createActiveTransactionsWorkspaceTabId,
  createMonitorTreeNodeKey,
  MONITOR_TREE_ITEMS,
} from './monitorTree';

assert.deepEqual(MONITOR_TREE_ITEMS, [
  {
    capability: DatabaseCapability.ACTIVE_TRANSACTION_INSPECTION,
    treeNodeType: TreeNodeType.ACTIVE_TRANSACTIONS,
    titleKey: 'workspace.ops.activeTransactions',
  },
]);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.MYSQL, DatabaseCapability.ACTIVE_TRANSACTION_INSPECTION),
  true,
);
assert.equal(
  isDatabaseCapabilitySupported(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.ACTIVE_TRANSACTION_INSPECTION),
  false,
);
assert.equal(createMonitorTreeNodeKey(42), 'dataSource_42-monitor');
assert.equal(createActiveTransactionsTreeNodeKey(42), 'dataSource_42-monitor-activeTransactions');
assert.equal(createActiveTransactionsWorkspaceTabId(42), 'activeTransactions-42');

const monitorTreeSource = readFileSync('src/blocks/NewTree/monitorTree.ts', 'utf8');
const treeConfigSource = readFileSync('src/blocks/NewTree/treeConfig.tsx', 'utf8');
const workspaceTabsSource = readFileSync('src/pages/main/workspace/components/WorkspaceTabs/index.tsx', 'utf8');
assert.doesNotMatch(monitorTreeSource, /isDatabaseCapabilitySupported|getMonitorTreeNodeTypes/);
assert.match(treeConfigSource, /isDatabaseCapabilitySupported/);
assert.match(treeConfigSource, /DatabaseCapability\.ACTIVE_TRANSACTION_INSPECTION|capability/);
assert.match(workspaceTabsSource, /isDatabaseCapabilitySupported/);
assert.match(workspaceTabsSource, /DatabaseCapability\.ACTIVE_TRANSACTION_INSPECTION/);

console.log('Monitor tree tests passed');
