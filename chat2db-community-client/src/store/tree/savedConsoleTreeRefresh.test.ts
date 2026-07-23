import {
  applyExistingTreeNodeRefresh,
  createSavedConsoleTreeNodeKey,
  LatestTreeRefreshTracker,
  loadExistingTreeNodeRefresh,
  reconcileTreeInteractionAfterRefresh,
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

  const result = await loadExistingTreeNodeRefresh(state.treeData, savedConsoleKey, async () => ({
    children: [refreshedConsole],
    total: 1,
  }));
  if (!result) {
    throw new Error('expected an existing saved-console node to refresh');
  }
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
  const result = await loadExistingTreeNodeRefresh(state.treeData, savedConsoleKey, async () => ({
    children: [],
    total: 0,
  }));
  if (!result) {
    throw new Error('expected an existing saved-console node to refresh');
  }
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

async function testRefreshesCollapsedDirectoryWithoutExpandingIt() {
  const state = {
    treeData: [createSavedConsoleNode()],
    selectedKeys: ['table_orders'],
    expandedKeys: [] as string[],
  };
  let requestCount = 0;
  const result = await loadExistingTreeNodeRefresh(state.treeData, savedConsoleKey, async () => {
    requestCount += 1;
    return {
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
  });
  if (!result) {
    throw new Error('expected a collapsed saved-console directory to refresh');
  }
  const nextState = {
    ...state,
    treeData: applyExistingTreeNodeRefresh(state.treeData, savedConsoleKey, result),
  };

  if (requestCount !== 1 || nextState.treeData[0].children?.[0].id !== 43) {
    throw new Error('collapsed saved-console directory did not receive refreshed children');
  }
  if (nextState.expandedKeys !== state.expandedKeys || nextState.expandedKeys.length !== 0) {
    throw new Error('background refresh expanded the collapsed saved-console directory');
  }
}

async function testRefreshSkipsUnavailableNodes() {
  let requestCount = 0;
  const load = async () => {
    requestCount += 1;
    return { children: [] };
  };

  await loadExistingTreeNodeRefresh(null, savedConsoleKey, load);
  await loadExistingTreeNodeRefresh([createSelectedNode()], savedConsoleKey, load);

  if (requestCount !== 0) {
    throw new Error(`expected unavailable nodes to skip refresh, got ${requestCount} requests`);
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

function testLatestRefreshSequenceWins() {
  const tracker = new LatestTreeRefreshTracker();
  const firstRequest = tracker.begin(savedConsoleKey);
  const secondRequest = tracker.begin(savedConsoleKey);
  const otherRequest = tracker.begin('other-saved-console-key');

  if (tracker.isLatest(savedConsoleKey, firstRequest)) {
    throw new Error('older saved-console refresh was accepted');
  }
  if (!tracker.isLatest(savedConsoleKey, secondRequest) || !tracker.isLatest('other-saved-console-key', otherRequest)) {
    throw new Error('latest saved-console refresh was rejected');
  }

  tracker.finish(savedConsoleKey, firstRequest);
  if (!tracker.isLatest(savedConsoleKey, secondRequest)) {
    throw new Error('an older refresh cleared the latest request');
  }
  tracker.finish(savedConsoleKey, secondRequest);
  const nextRequest = tracker.begin(savedConsoleKey);
  if (nextRequest === firstRequest || tracker.isLatest(savedConsoleKey, firstRequest)) {
    throw new Error('completed refresh sequence was reused');
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
  testRefreshSkipsUnavailableNodes(),
])
  .then(() => {
    testSavedConsoleKeysAreStable();
    testLatestRefreshSequenceWins();
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
