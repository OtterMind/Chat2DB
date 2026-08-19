import {
  applyExistingTreeNodeRefresh,
  createSavedConsoleTreeNodeKey,
  reconcileTreeInteractionAfterRefresh,
  reconcileTreeStateAfterRefresh,
} from './backgroundRefresh';
import type { TreeNodeData } from '@/typings';
import { SAVED_CONSOLE_UPDATED_EVENT } from '@/constants/workspace';
import { DatabaseTypeCode } from '@/constants/common';
import { emitSavedConsoleRecordUpdated, emitSavedConsoleUpdated } from '@/utils/savedConsoleEvents';

const savedConsoleKey = 'dataSource_1-database_chat2db-schema_undefined-consoles_chat2dbCatalogue';

function createSavedConsoleNode(children?: TreeNodeData[]): TreeNodeData {
  return {
    key: savedConsoleKey,
    originalTitle: 'Queries',
    treeNodeType: 'saveConsoles' as TreeNodeData['treeNodeType'],
    isLeaf: false,
    extraParams: { dataSourceId: 1, databaseName: 'chat2db' },
    children,
  };
}

function createSelectedNode(): TreeNodeData {
  return {
    key: 'table_orders',
    originalTitle: 'orders',
    treeNodeType: 'table' as TreeNodeData['treeNodeType'],
    isLeaf: true,
    extraParams: { dataSourceId: 1, databaseName: 'chat2db', tableName: 'orders' },
  };
}

function createSavedConsoleLeaf(id: number, title: string): TreeNodeData {
  return {
    key: createSavedConsoleTreeNodeKey({
      dataSourceId: 1,
      databaseName: 'chat2db',
      consoleId: id,
    }),
    id,
    originalTitle: title,
    treeNodeType: 'saveConsole' as TreeNodeData['treeNodeType'],
    isLeaf: true,
    extraParams: { dataSourceId: 1, databaseName: 'chat2db' },
  };
}

async function testRefreshPreservesTreeInteractionState() {
  const selectedNode = createSavedConsoleLeaf(42, 'Old saved query');
  const state = {
    treeData: [createSavedConsoleNode([selectedNode]), createSelectedNode()],
    currentTreeNode: selectedNode,
    selectedKeys: [selectedNode.key],
    expandedKeys: [savedConsoleKey, 'database_chat2db'],
  };
  const refreshedConsole = createSavedConsoleLeaf(42, 'Updated saved query');

  const result = {
    children: [refreshedConsole],
    total: 1,
  };
  const refreshedTreeData = applyExistingTreeNodeRefresh(state.treeData, savedConsoleKey, result);
  const nextState = {
    ...state,
    treeData: refreshedTreeData,
    ...reconcileTreeInteractionAfterRefresh(refreshedTreeData, state.selectedKeys, state.currentTreeNode),
  };

  if (nextState.currentTreeNode !== refreshedConsole) {
    throw new Error('background refresh did not rebind currentTreeNode to the refreshed saved console');
  }
  if (nextState.selectedKeys !== state.selectedKeys) {
    throw new Error('background refresh changed selectedKeys');
  }
  if (nextState.expandedKeys !== state.expandedKeys) {
    throw new Error('background refresh changed expandedKeys');
  }
  if (nextState.treeData[0].children?.[0].id !== 42 || nextState.treeData[0].childCount !== 1) {
    throw new Error('background refresh did not update the saved-console node');
  }
}

async function testRefreshClearsDeletedSavedConsoleSelection() {
  const selectedNode = createSavedConsoleLeaf(42, 'Saved query');
  const state = {
    treeData: [createSavedConsoleNode([selectedNode])],
    currentTreeNode: selectedNode,
    selectedKeys: [selectedNode.key],
  };
  const result = {
    children: [],
    total: 0,
  };
  const refreshedTreeData = applyExistingTreeNodeRefresh(state.treeData, savedConsoleKey, result);
  const interactionState = reconcileTreeInteractionAfterRefresh(
    refreshedTreeData,
    state.selectedKeys,
    state.currentTreeNode,
  );

  if (interactionState.currentTreeNode !== null || interactionState.selectedKeys.length !== 0) {
    throw new Error('deleted saved console left stale tree interaction state');
  }
}

function testTreeStateReconciliationClearsRemovedInteractionTargets() {
  const retainedNode = {
    key: 'group_retained',
    treeNodeType: 'group' as TreeNodeData['treeNodeType'],
    isLeaf: false,
    extraParams: {},
    children: [],
  } as TreeNodeData;
  const removedNode = createSavedConsoleLeaf(42, 'Removed query');
  const interactionState = reconcileTreeStateAfterRefresh(
    [retainedNode],
    [removedNode.key, retainedNode.key],
    removedNode,
    [savedConsoleKey, retainedNode.key],
    removedNode.key,
  );

  if (
    interactionState.currentTreeNode !== null ||
    interactionState.selectedKeys.length !== 1 ||
    interactionState.selectedKeys[0] !== retainedNode.key ||
    interactionState.expandedKeys.length !== 1 ||
    interactionState.expandedKeys[0] !== retainedNode.key ||
    interactionState.scrollTargetKey !== null
  ) {
    throw new Error(`removed tree interaction targets survived reconciliation: ${JSON.stringify(interactionState)}`);
  }
}

function testTreeStateReconciliationDropsExpandedNodesWithoutLoadedChildren() {
  const unloadedDataSource = {
    key: 'dataSource_1',
    treeNodeType: 'dataSource' as TreeNodeData['treeNodeType'],
    isLeaf: false,
    extraParams: { dataSourceId: 1 },
  } as TreeNodeData;
  const group = {
    key: 'group_1',
    treeNodeType: 'group' as TreeNodeData['treeNodeType'],
    isLeaf: false,
    extraParams: {},
    children: [unloadedDataSource],
  } as TreeNodeData;

  const interactionState = reconcileTreeStateAfterRefresh(
    [group],
    [],
    null,
    [group.key, unloadedDataSource.key],
    null,
  );

  if (interactionState.expandedKeys.length !== 1 || interactionState.expandedKeys[0] !== group.key) {
    throw new Error(`unloaded child remained expanded: ${JSON.stringify(interactionState.expandedKeys)}`);
  }
}

async function testRefreshesCollapsedDirectoryWithoutExpandingIt() {
  const state = {
    treeData: [createSavedConsoleNode()],
    selectedKeys: ['table_orders'],
    expandedKeys: [] as string[],
  };
  const result = {
    children: [
      {
        key: `${savedConsoleKey}-console_43`,
        id: 43,
        originalTitle: 'New saved query',
        treeNodeType: 'saveConsole' as TreeNodeData['treeNodeType'],
        isLeaf: true,
        extraParams: { dataSourceId: 1, databaseName: 'chat2db' },
      },
    ],
    total: 1,
  };
  const nextState = {
    ...state,
    treeData: applyExistingTreeNodeRefresh(state.treeData, savedConsoleKey, result),
  };

  if (nextState.treeData[0].children?.[0].id !== 43) {
    throw new Error('collapsed saved-console directory did not receive refreshed children');
  }
  if (nextState.expandedKeys !== state.expandedKeys || nextState.expandedKeys.length !== 0) {
    throw new Error('background refresh expanded the collapsed saved-console directory');
  }
}

function testSavedConsoleKeysAreStable() {
  const params = {
    dataSourceId: 1,
    databaseName: 'chat2db',
    schemaName: undefined,
    consoleId: 42,
  };
  const firstKey = createSavedConsoleTreeNodeKey(params);
  const secondKey = createSavedConsoleTreeNodeKey(params);
  if (firstKey !== secondKey || firstKey.includes('uuid_')) {
    throw new Error(`saved-console tree key is not stable: ${firstKey} / ${secondKey}`);
  }
}

function testSavedConsoleUpdateEventScope() {
  const events: Event[] = [];
  const target = {
    dispatchEvent(event: Event) {
      events.push(event);
      return true;
    },
  } as EventTarget;

  const emitted = emitSavedConsoleUpdated(
    {
      dataSourceId: 1,
      databaseType: DatabaseTypeCode.MYSQL,
      databaseName: 'chat2db',
    },
    target,
  );
  if (!emitted || events.length !== 1 || events[0].type !== SAVED_CONSOLE_UPDATED_EVENT) {
    throw new Error('expected a scoped saved-console update event');
  }
  const detail = (events[0] as CustomEvent).detail;
  if (detail.dataSourceId !== 1 || detail.databaseType !== DatabaseTypeCode.MYSQL) {
    throw new Error(`unexpected saved-console update detail: ${JSON.stringify(detail)}`);
  }

  const incompleteScopeEmitted = emitSavedConsoleUpdated({ dataSourceId: 1 }, target);
  if (incompleteScopeEmitted || events.length !== 1) {
    throw new Error('incomplete saved-console scope should not emit an event');
  }
}

function testRenamedSavedConsoleEmitsScopedUpdate() {
  const events: Event[] = [];
  const target = {
    dispatchEvent(event: Event) {
      events.push(event);
      return true;
    },
  } as EventTarget;

  const emitted = emitSavedConsoleRecordUpdated(
    {
      dataSourceId: 1,
      type: DatabaseTypeCode.MYSQL,
      databaseName: 'chat2db',
      schemaName: 'public',
    },
    target,
  );
  const detail = (events[0] as CustomEvent | undefined)?.detail;
  if (
    !emitted ||
    events.length !== 1 ||
    detail?.dataSourceId !== 1 ||
    detail?.databaseType !== DatabaseTypeCode.MYSQL ||
    detail?.databaseName !== 'chat2db' ||
    detail?.schemaName !== 'public'
  ) {
    throw new Error(`renamed saved console emitted an unexpected update: ${JSON.stringify(detail)}`);
  }
}

Promise.all([
  testRefreshPreservesTreeInteractionState(),
  testRefreshClearsDeletedSavedConsoleSelection(),
  testRefreshesCollapsedDirectoryWithoutExpandingIt(),
])
  .then(() => {
    testSavedConsoleKeysAreStable();
    testTreeStateReconciliationClearsRemovedInteractionTargets();
    testTreeStateReconciliationDropsExpandedNodesWithoutLoadedChildren();
    testSavedConsoleUpdateEventScope();
    testRenamedSavedConsoleEmitsScopedUpdate();
  })
  .then(() => {
    console.log('Saved console tree refresh tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
