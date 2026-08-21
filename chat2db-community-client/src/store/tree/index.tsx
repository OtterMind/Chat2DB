import { openSchemaSyncModal } from '@/blocks/NewTree/functions/schemaSync';
import {
  ILoadDataOptions,
  ILoadDataResult,
  normalizeTreeNodeLoadResult,
  treeConfig,
} from '@/blocks/NewTree/treeConfig';
import { TreeNodeType, initUserConfigTree } from '@/constants';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { getRuntimeEditionCapabilities } from '@/hooks/useRuntimeEditionCapabilities';
import { dataSourceTreeService } from '@/database';
import aiDataCollectionService from '@/service/aiDataCollection';
import connectionService from '@/service/connection';
import { IConnectionDetails, IUserConfigTree, TreeNodeData } from '@/typings';
import { GetTreeNodeKeyParams, UpdatePositionInTree } from '@/typings/tree';
import { findNode, getParentNode, removeSubkeys, searchTreeNodes } from '@/utils';
import { filterTreeNodesForDisplay } from '@/utils/filterTreeNodes';
import React from 'react';
import { PersistOptions, devtools, persist } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';
import { StateCreator } from 'zustand/vanilla';
import { useAIStore } from '../ai';
import {
  applyExistingTreeNodeRefresh,
  reconcileTreeInteractionAfterRefresh,
  reconcileTreeStateAfterRefresh,
} from './backgroundRefresh';
import { DataSourceIdentityColorPatch, patchDataSourceIdentityTree } from './dataSourceIdentity';
import { collectDataSourceNodes, pruneDataSourceRuntimeAvailability } from './dataSourceList';
import { shouldReuseTreeNodeChildren } from './treeNodeLoadState';
import { hydrateDataSourceAfterMutation } from './dataSourceMutationRefresh';
import { applyHiddenTreeNodeChanges, HiddenTreeNodeStateCoordinator } from './hiddenTreeNodeState';
import { LatestLoadCoordinator, loadNamespaceTree } from './loadNamespaceTree';
import {
  appendExpandedTreeKey,
  isDataSourceTreeNodeKey,
  removeTreeNodeByKey,
  resolveLoadedTreeData,
} from './treeDataUpdate';
import { neatenDataSourceTreeNode, neatenDataSourcesList, neatenTreeData } from './utils';
import {
  transitionDataSourceRuntimeAvailability,
  type DataSourceRuntimeAvailability,
  type DataSourceRuntimeAvailabilityById,
  type DataSourceRuntimeAvailabilityGenerationById,
} from '@/utils/editorDataSourceLifecycle';

export type FocusTreeNode = {
  dataSourceId: number;
  dataSourceName: string;
  databaseType: string;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
} | null;

export interface TreeState {
  treeData: TreeNodeData[] | null;
  treeDataRevision: number;
  focusId: number | string | null;
  focusTreeNode: FocusTreeNode;
  editingTreeNode: TreeNodeData | null;
  currentTreeNode: TreeNodeData | null;
  currentLoadingTreeNode: TreeNodeData | null;
  dataSourceList: TreeNodeData[] | null;
  runtimeAvailabilityByDataSourceId: DataSourceRuntimeAvailabilityById;
  runtimeAvailabilityGenerationByDataSourceId: DataSourceRuntimeAvailabilityGenerationById;
  selectedKeys: React.Key[];
  scrollTargetKey: React.Key | null;
  treeRef: any;
  connectionDetail: IConnectionDetails | null;
  isModalVisible: boolean;
  dataList: { key: React.Key; title: string }[];
  expandedKeys: React.Key[];
  searchBarValue: string;
  // This value is escaped before tree search so brackets and other special characters remain valid.
  regularSearchBarValue: string;
  searchResultKeys: string[] | null;
  searchResult: TreeNodeData[] | null;
  userConfigTree: IUserConfigTree;
  // Hidden node id
  hiddenTreeNodeIds: {
    [key: string]: string[];
  } | null;
}

export const initTreeState = {
  treeData: null,
  treeDataRevision: 0,
  focusId: null,
  focusTreeNode: null,
  editingTreeNode: null,
  currentTreeNode: null,
  dataSourceList: null,
  runtimeAvailabilityByDataSourceId: {},
  runtimeAvailabilityGenerationByDataSourceId: {},
  selectedKeys: [],
  scrollTargetKey: null,
  treeRef: null,
  connectionDetail: null,
  isModalVisible: false,
  dataList: [],
  expandedKeys: [],
  searchBarValue: '',
  regularSearchBarValue: '',
  searchResultKeys: null,
  // Search results
  searchResult: null,
  currentLoadingTreeNode: null,
  userConfigTree: initUserConfigTree,
  // Hidden node id
  hiddenTreeNodeIds: null,
};

export interface TreeAction {
  clearTreeStore: () => void;
  setEditingTreeNode: (editingTreeNode: TreeState['editingTreeNode']) => void;
  setCurrentTreeNode: (editingTreeNode: TreeState['currentTreeNode']) => void;
  createGroup: (parentId?: number) => void;
  // Move a group or data source to a specified group
  moveToGroup: (props: UpdatePositionInTree) => void;
  setTreeData: (treeData: TreeState['treeData'] | any) => void;
  getTreeData: (props?: { refresh?: boolean; force?: boolean; throwOnError?: boolean }) => Promise<boolean>;
  refreshTreeData: () => Promise<boolean>;
  refreshDataSourceAfterMutation: (dataSourceId: number) => Promise<void>;
  // Database structure synchronization
  schemaSync: () => void;
  setSelectedKeys: (selectedKeys: TreeState['selectedKeys']) => void;
  setScrollTargetKey: (scrollTargetKey: TreeState['scrollTargetKey']) => void;
  setTreeRef: (treeRef: any) => void;
  deleteGroup: (treeNodeData: TreeNodeData) => Promise<void>;
  addDataSource: (dataSource: any) => void;
  editorDataSource: (dataSource: any) => void;
  setIsModalVisible: (isModalVisible: TreeState['isModalVisible']) => void;
  setConnectionDetail: (connectionDetail: TreeState['connectionDetail']) => void;
  handleLoadData: (nodeData: TreeNodeData, options?: ILoadDataOptions) => Promise<ILoadDataResult>;
  setExpandedKeys: (expandedKeys: React.Key[]) => void;
  toggleExpandedKeys: (key: React.Key) => void;
  deleteDataSource: (dataSource: any) => Promise<void>;
  setSearchBarValue: (searchBarValue: string) => void;
  setSearchResultKeys: (searchResultKeys: TreeState['searchResultKeys']) => void;
  setSearchResult: (searchResult: any) => void;
  setCurrentLoadingTreeNode: (currentLoadingTreeNode: TreeNodeData | null) => void;
  getDataSourceList: (props?: { refresh?: boolean }) => void;
  generateDataSourceList: (data: TreeNodeData[]) => void;
  updateDataSourceIdentity: (patch: DataSourceIdentityColorPatch) => void;
  setDataSourceRuntimeAvailability: (dataSourceId: number, availability?: DataSourceRuntimeAvailability) => void;
  restoreDataSourceRuntimeAvailability: (dataSourceId: number, expectedGeneration: number) => void;
  deleteAiDataCollection: (treeNodeData: TreeNodeData, handleLoad: any) => Promise<void>;
  deleteAiDataCollectionElement: (treeNodeData: TreeNodeData, handleLoadData: any) => Promise<void>;
  refreshAiDataCollection: (dataSourceId: number) => void;
  changeUserConfigTree: (type: string, value: any) => void;
  // Update node data through key
  updateTreeNodeDataByKey: (key: React.Key, getTreeNodeKeyParams?: GetTreeNodeKeyParams) => void;
  // Refresh data with details
  updateTreeNodeDataByDetail: (props: GetTreeNodeKeyParams) => void;
  // Refresh an existing node without changing the current tree interaction state.
  refreshTreeNodeDataInBackground: (props: GetTreeNodeKeyParams) => Promise<void>;
  getTreeNodeKey: (props: GetTreeNodeKeyParams) => string;
  // close connection
  closeConnection: (dataSourceId: number) => void;
  // Update the name of a node based on nodeId
  updateOriginalTitleByNodeId: (nodeKey: string, originalTitle: string) => void;
  // Get the child nodes under a certain node. If the child node is undefined, request the child node.
  getChildrenByNodeId: (nodeId: string) => TreeNodeData[];
  initHiddenTreeNodeIds: () => void;
  addOrDeleteShowTreeNodeIds: (
    dataSourceId: number,
    changedKeys?: {
      add: (string | null | undefined)[];
      delete: (string | null | undefined)[];
    },
  ) => void;
}

type RootTreeLoadResult =
  | { committed: true }
  | { committed: false }
  | { committed: false; error: unknown };

const rootTreeLoadCoordinator = new LatestLoadCoordinator<string, RootTreeLoadResult>(() => ({ committed: false }));
const treeNodeLoadCoordinator = new LatestLoadCoordinator<React.Key, ILoadDataResult>(() => ({
  children: [],
  committed: false,
}));
const hiddenTreeNodeStateCoordinator = new HiddenTreeNodeStateCoordinator<
  NonNullable<TreeState['hiddenTreeNodeIds']>
>();
const ROOT_TREE_REFRESH_KEY = 'root';
let treeStoreLifecycleVersion = 0;

const invalidateDataSourceTreeRequests = (dataSourceId: number) => {
  treeNodeLoadCoordinator.invalidateMatching((key) => isDataSourceTreeNodeKey(key, dataSourceId));
};

const invalidateTreeRequests = () => {
  treeStoreLifecycleVersion += 1;
  rootTreeLoadCoordinator.invalidateAll();
  treeNodeLoadCoordinator.invalidateAll();
  hiddenTreeNodeStateCoordinator.reset();
};

export type TreeStore = TreeState & TreeAction;

export const createTreeAction: StateCreator<TreeStore, [['zustand/devtools', never]], [], TreeAction> = (set, get) => ({
  setEditingTreeNode: (editingTreeNode) => {
    set({ editingTreeNode });
  },
  setCurrentTreeNode: (currentTreeNode) => {
    set({ currentTreeNode });
  },
  clearTreeStore: () => {
    invalidateTreeRequests();
    set(initTreeState);
  },
  refreshTreeData: () => get().getTreeData({ refresh: true }),
  refreshDataSourceAfterMutation: async (dataSourceId) => {
    await hydrateDataSourceAfterMutation(dataSourceId, {
      refreshTreeData: () => get().getTreeData({ refresh: true, throwOnError: true }),
      getDataSourceList: () => get().dataSourceList,
      setSelectedKeys: get().setSelectedKeys,
      setScrollTargetKey: get().setScrollTargetKey,
      loadData: (node) => get().handleLoadData(node),
    });
  },
  schemaSync: () => {
    // currently selected node
    const currentTreeNode = get().currentTreeNode;
    let selectDatabase: any = undefined;

    if (currentTreeNode?.extraParams?.dataSourceId !== undefined) {
      const { dataSourceId, databaseName, schemaName } = currentTreeNode.extraParams;
      selectDatabase = {
        dataSourceId,
        databaseName,
        schemaName,
      };
    }
    openSchemaSyncModal(selectDatabase);
  },
  getTreeData: (props) => {
    const refresh = props?.refresh === true;
    const force = props?.force === true;
    const throwOnError = props?.throwOnError === true;
    const loadPromise = rootTreeLoadCoordinator.run(
      ROOT_TREE_REFRESH_KEY,
      {
        supersede: refresh || force,
        priority: refresh ? 1 : 0,
      },
      async (isCurrent): Promise<RootTreeLoadResult> => {
        get().initHiddenTreeNodeIds();
        const result = await loadNamespaceTree(() => connectionService.getNamespaceList({ refresh }));
        if (!isCurrent()) {
          return { committed: false };
        }
        if (!result.ok) {
          if (get().treeData === null) {
            get().setTreeData([]);
            get().generateDataSourceList([]);
          }
          return { committed: false, error: result.error };
        }

        if (refresh) {
          treeNodeLoadCoordinator.invalidateAll();
          set({ currentLoadingTreeNode: null });
        }
        const freshTreeData = neatenTreeData(result.items);
        const treeData = resolveLoadedTreeData(freshTreeData, get().treeData, refresh);
        get().setTreeData(treeData);
        get().generateDataSourceList(treeData);
        if (refresh || force) {
          const interactionState = reconcileTreeStateAfterRefresh(
            treeData,
            get().selectedKeys,
            get().currentTreeNode,
            get().expandedKeys,
            get().scrollTargetKey,
          );
          set({
            ...interactionState,
            treeDataRevision: get().treeDataRevision + 1,
          });
        }
        return { committed: true };
      },
    );

    return loadPromise.then((result) => {
      if (!result.committed && 'error' in result && throwOnError) {
        throw result.error;
      }
      return result.committed;
    });
  },
  // Load data
  handleLoadData: (nodeData, config) => {
    const { refresh = false, closeExpandTreeNode, preserveInteraction = false } = config || {};
    const { key, treeNodeType } = nodeData;
    const currentNode = findNode(key, get().treeData) || nodeData;
    const rootDataSourceId =
      currentNode.treeNodeType === TreeNodeType.DATA_SOURCE
        ? currentNode.extraParams.dataSourceId
        : undefined;
    const shouldReuseCurrentChildren = shouldReuseTreeNodeChildren({
      children: currentNode.children,
      refresh,
      isDataSourceRoot: rootDataSourceId !== undefined,
      runtimeAvailability:
        rootDataSourceId === undefined ? undefined : get().runtimeAvailabilityByDataSourceId[rootDataSourceId],
    });
    if (shouldReuseCurrentChildren && !treeNodeLoadCoordinator.hasPending(key)) {
      if (closeExpandTreeNode !== true) {
        const expandedKeys = appendExpandedTreeKey(get().expandedKeys, key);
        if (expandedKeys !== get().expandedKeys) {
          get().setExpandedKeys(expandedKeys);
        }
      }
      return Promise.resolve({ children: currentNode.children, committed: true });
    }

    const interactionTreeData = preserveInteraction ? null : get().treeData;
    if (!preserveInteraction) {
      get().setCurrentTreeNode(currentNode);
      get().setSelectedKeys([key]);
    }

    return treeNodeLoadCoordinator.run(key, { supersede: refresh }, async (isCurrent) => {
      const requestNode = findNode(key, get().treeData) || nodeData;
      const requestDataSourceId =
        requestNode.treeNodeType === TreeNodeType.DATA_SOURCE
          ? requestNode.extraParams.dataSourceId
          : undefined;
      const shouldReuseRequestChildren = shouldReuseTreeNodeChildren({
        children: requestNode.children,
        refresh,
        isDataSourceRoot: requestDataSourceId !== undefined,
        runtimeAvailability:
          requestDataSourceId === undefined
            ? undefined
            : get().runtimeAvailabilityByDataSourceId[requestDataSourceId],
      });
      if (shouldReuseRequestChildren) {
        return { children: requestNode.children, committed: true };
      }

      const getChildren = treeConfig[treeNodeType].getChildren;
      if (!getChildren) {
        return { children: requestNode.children || [], committed: false };
      }

      get().setCurrentLoadingTreeNode(requestNode);

      try {
        const response = await getChildren({ ...requestNode.extraParams, refresh });
        const loadResult = normalizeTreeNodeLoadResult(response);
        if (!isCurrent()) {
          return { children: loadResult.children, committed: false };
        }

        const latestNode = findNode(key, get().treeData);
        if (!latestNode) {
          return { children: loadResult.children, committed: false };
        }
        if (requestDataSourceId !== undefined) {
          get().setDataSourceRuntimeAvailability(requestDataSourceId, 'available');
        }
        const children = resolveLoadedTreeData(loadResult.children, latestNode.children ?? null, refresh);
        const currentTreeData = get().treeData;
        if (!currentTreeData) {
          return { children, committed: false };
        }
        const nextTreeData = applyExistingTreeNodeRefresh(currentTreeData, key, {
          children,
          total: loadResult.total,
        });
        if (nextTreeData === currentTreeData) {
          return { children, committed: false };
        }
        get().setTreeData(nextTreeData);
        return { children, committed: true };
      } catch (error) {
        if (isCurrent() && requestDataSourceId !== undefined) {
          get().setDataSourceRuntimeAvailability(requestDataSourceId, 'unavailable');
        }
        throw error;
      } finally {
        if (isCurrent() && get().currentLoadingTreeNode?.key === key) {
          get().setCurrentLoadingTreeNode(null);
        }
      }
    }).then((result) => {
      // Selection and expansion belong to each caller even when the data request is shared.
      if (result.committed && findNode(key, get().treeData)) {
        if (preserveInteraction && get().treeData) {
          set(
            reconcileTreeInteractionAfterRefresh(
              get().treeData!,
              get().selectedKeys,
              get().currentTreeNode,
            ),
          );
        }
        let expandedKeys = get().expandedKeys;
        if (interactionTreeData) {
          expandedKeys = removeSubkeys(expandedKeys, interactionTreeData, key);
        }
        if (closeExpandTreeNode !== true) {
          expandedKeys = appendExpandedTreeKey(expandedKeys, key);
        }
        get().setExpandedKeys(expandedKeys);
      }
      return result;
    });
  },
  createGroup: (parentId) => {
    const params = {
      name: 'New Group',
      parentId: parentId,
    };
    connectionService.createNamespace(params).then((res) => {
      rootTreeLoadCoordinator.invalidate(ROOT_TREE_REFRESH_KEY);
      const t = {
        id: res,
        name: params.name,
      };
      const newTreeData = get().treeData;
      if (!newTreeData) {
        get().getTreeData();
        return;
      }

      const newGroup = {
        key: `group_${t.id}`,
        id: t.id,
        originalTitle: t.name,
        title: null,
        treeNodeType: TreeNodeType.GROUP,
        extraParams: {
          groupId: t.id,
        },
        children: [],
      };

      if (parentId) {
        // Expand current group
        get().setExpandedKeys([...get().expandedKeys, `group_${parentId}`]);
        findNode(`group_${parentId}`, newTreeData)?.children?.push(newGroup);
      } else {
        newTreeData.push(newGroup);
      }

      set({ treeData: [...newTreeData] });
      get().setEditingTreeNode(newGroup);
      get().setSelectedKeys([`group_${t.id}`]);
      get().setScrollTargetKey(`group_${t.id}`);
    });
  },
  moveToGroup: (params) => {
    connectionService.updatePosition(params).then(() => {
      get().getTreeData({ force: true });
    });
  },
  deleteGroup: (treeNodeData) => {
    return connectionService.deleteNamespace({ id: treeNodeData.id! }).then(async () => {
      await get().getTreeData({ force: true });
    });
  },
  setTreeData: (treeData) => {
    if (typeof treeData === 'function') {
      const _treeData = treeData(get().treeData);
      set({ treeData: _treeData });
      if (get().searchBarValue && _treeData) {
        const visibleTreeData = filterTreeNodesForDisplay(_treeData, {
          hiddenTreeNodeIds: get().hiddenTreeNodeIds,
          aiDataCollectionEnabled: getRuntimeEditionCapabilities().aiDataCollection,
        });
        const { matchedNodes, matchedKeys, parentIdsWithMatches } = searchTreeNodes(
          visibleTreeData,
          get().regularSearchBarValue,
        );
        get().setSearchResult(matchedNodes);
        get().setSearchResultKeys(matchedKeys);
        get().setExpandedKeys([...get().expandedKeys, ...parentIdsWithMatches]);
      }
    } else {
      set({ treeData });
      if (get().searchBarValue && treeData) {
        const visibleTreeData = filterTreeNodesForDisplay(treeData, {
          hiddenTreeNodeIds: get().hiddenTreeNodeIds,
          aiDataCollectionEnabled: getRuntimeEditionCapabilities().aiDataCollection,
        });
        const { matchedNodes, matchedKeys, parentIdsWithMatches } = searchTreeNodes(
          visibleTreeData,
          get().regularSearchBarValue,
        );
        get().setSearchResult(matchedNodes);
        get().setSearchResultKeys(matchedKeys);
        get().setExpandedKeys(parentIdsWithMatches);
      }
    }
  },
  setSelectedKeys: (selectedKeys) => {
    set({ selectedKeys });
  },
  setScrollTargetKey: (scrollTargetKey) => {
    set({ scrollTargetKey });
  },
  setTreeRef: (treeRef) => {
    set({ treeRef });
  },
  addDataSource: (dataSource) => {
    rootTreeLoadCoordinator.invalidate(ROOT_TREE_REFRESH_KEY);
    const newTreeData = get().treeData;
    const newDataSource = neatenDataSourceTreeNode(dataSource)!;
    if (!newTreeData) return;

    if (dataSource.spaceId) {
      const groupId = `group_${dataSource.spaceId}`;
      findNode(groupId, newTreeData)?.children?.push(newDataSource);
      get().setExpandedKeys([...get().expandedKeys, groupId]);
    } else {
      newTreeData?.push(newDataSource);
    }
    set({ treeData: [...newTreeData!] });
    get().generateDataSourceList(newTreeData!);
    // Select the new data source
    get().setSelectedKeys([newDataSource.key]);
    get().setScrollTargetKey(newDataSource.key);
    // If the connection succeeds, show the new AI dataset prompt.
    get().handleLoadData(newDataSource);
    // .then(() => {
    //   createAiDataCollectionTips(newDataSource.extraParams);
    // });
  },
  deleteDataSource: (dataSource) => {
    return new Promise<void>((resolve, reject) => {
      connectionService
        .remove({ id: dataSource.id })
        .then(() => {
          rootTreeLoadCoordinator.invalidate(ROOT_TREE_REFRESH_KEY);
          invalidateDataSourceTreeRequests(dataSource.id);
          if (
            get().currentLoadingTreeNode &&
            isDataSourceTreeNodeKey(get().currentLoadingTreeNode!.key, dataSource.id)
          ) {
            get().setCurrentLoadingTreeNode(null);
          }
          const newTreeDataAfterDelete = removeTreeNodeByKey(
            get().treeData || [],
            `dataSource_${dataSource.id}`,
          );
          const interactionState = reconcileTreeStateAfterRefresh(
            newTreeDataAfterDelete,
            get().selectedKeys,
            get().currentTreeNode,
            get().expandedKeys,
            get().scrollTargetKey,
          );
          set({ treeData: newTreeDataAfterDelete, ...interactionState });
          get().setDataSourceRuntimeAvailability(dataSource.id, undefined);
          get().generateDataSourceList(newTreeDataAfterDelete);
          resolve();
          // Clean up deleted data source data
          dataSourceTreeService.cleanUpJunkData(dataSource.id);
        })
        .catch(() => {
          reject();
        });
    });
  },
  editorDataSource: (dataSourceDetails) => {
    rootTreeLoadCoordinator.invalidate(ROOT_TREE_REFRESH_KEY);
    invalidateDataSourceTreeRequests(dataSourceDetails.id);
    if (
      get().currentLoadingTreeNode &&
      isDataSourceTreeNodeKey(get().currentLoadingTreeNode!.key, dataSourceDetails.id)
    ) {
      get().setCurrentLoadingTreeNode(null);
    }
    const newTreeData = get().treeData;
    if (!newTreeData) {
      get().getTreeData();
      return;
    }
    const parentNode = getParentNode(`dataSource_${dataSourceDetails.id}`, newTreeData);
    const newTreeNode = neatenDataSourceTreeNode(dataSourceDetails);
    if (!newTreeNode) return;
    const siblings = parentNode?.children ?? newTreeData;
    const index = siblings.findIndex((item) => item.key === `dataSource_${dataSourceDetails.id}`);
    if (index < 0) {
      get().getTreeData();
      return;
    }
    siblings.splice(index, 1, newTreeNode);
    const nextTreeData = [...newTreeData];
    const dataSourceList = collectDataSourceNodes(nextTreeData);
    // If it was originally expanded and needs to be collapsed
    const interactionState = reconcileTreeStateAfterRefresh(
      nextTreeData,
      get().selectedKeys,
      get().currentTreeNode,
      get().expandedKeys.filter((item) => item !== newTreeNode.key),
      get().scrollTargetKey,
    );
    set({
      treeData: nextTreeData,
      ...interactionState,
      dataSourceList,
      runtimeAvailabilityByDataSourceId: pruneDataSourceRuntimeAvailability(
        dataSourceList,
        get().runtimeAvailabilityByDataSourceId,
      ),
    });
  },
  setIsModalVisible: (isModalVisible) => {
    set({ isModalVisible });
  },
  setConnectionDetail: (connectionDetail) => {
    set({ connectionDetail });
  },
  setExpandedKeys: (expandedKeys) => {
    const uniqueKeys = Array.from(new Set(expandedKeys));
    set({ expandedKeys: uniqueKeys });
  },
  // Remove an existing expanded key, or add it when absent.
  toggleExpandedKeys: (key) => {
    const expandedKeys = get().expandedKeys;
    if (expandedKeys.includes(key)) {
      set({ expandedKeys: expandedKeys.filter((item) => item !== key) });
    } else {
      set({ expandedKeys: [...expandedKeys, key] });
    }
  },
  setSearchBarValue: (searchBarValue) => {
    function escapeRegExp(string) {
      return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); // $& represents the matched substring
    }
    set({
      searchBarValue,
      regularSearchBarValue: escapeRegExp(searchBarValue),
      searchResultKeys: null,
      searchResult: null,
    });
  },
  setSearchResultKeys: (searchResultKeys) => {
    set({ searchResultKeys });
  },
  setSearchResult: (searchResult) => {
    set({ searchResult });
  },
  setCurrentLoadingTreeNode: (currentLoadingTreeNode) => {
    set({ currentLoadingTreeNode });
  },
  getDataSourceList: (props) => {
    connectionService
      .getList({
        pageNo: 1,
        pageSize: 1000,
        refresh: props?.refresh,
      })
      .then((res) => {
        const _dataSourceList = neatenDataSourcesList(res.data || []);
        set({ dataSourceList: _dataSourceList });
      });
  },
  generateDataSourceList: (treeData) => {
    const dataSourceList = collectDataSourceNodes(treeData);
    const runtimeAvailabilityByDataSourceId = pruneDataSourceRuntimeAvailability(
      dataSourceList,
      get().runtimeAvailabilityByDataSourceId,
    );
    set({ dataSourceList, runtimeAvailabilityByDataSourceId });
  },
  updateDataSourceIdentity: (patch) => {
    const patchSingleNode = (node: TreeNodeData | null) =>
      node ? patchDataSourceIdentityTree([node], patch)?.[0] || node : null;
    set({
      treeData: patchDataSourceIdentityTree(get().treeData, patch),
      dataSourceList: patchDataSourceIdentityTree(get().dataSourceList, patch),
      searchResult: patchDataSourceIdentityTree(get().searchResult, patch),
      currentTreeNode: patchSingleNode(get().currentTreeNode),
      editingTreeNode: patchSingleNode(get().editingTreeNode),
      currentLoadingTreeNode: patchSingleNode(get().currentLoadingTreeNode),
    });
  },
  setDataSourceRuntimeAvailability: (dataSourceId, availability) => {
    set((state) => transitionDataSourceRuntimeAvailability(state, dataSourceId, availability) || {});
  },
  restoreDataSourceRuntimeAvailability: (dataSourceId, expectedGeneration) => {
    set(
      (state) =>
        transitionDataSourceRuntimeAvailability(state, dataSourceId, 'available', expectedGeneration) || {},
    );
  },
  deleteAiDataCollection: (treeNodeData, handleLoad) => {
    return aiDataCollectionService.deleteAiDataCollection({ id: treeNodeData.id! }).then(() => {
      const parentNode = getParentNode(treeNodeData.key, get().treeData);
      if (parentNode) {
        handleLoad(parentNode, {
          refresh: true,
        });
      } else {
        get().getTreeData();
      }

      useAIStore.getState().getDataCollectionList();
    });
  },
  deleteAiDataCollectionElement: (treeNodeData, handleLoad) => {
    const elements = [
      {
        id: treeNodeData.id!,
        dataSourceId: treeNodeData.extraParams.dataSourceId!,
        schemaName: treeNodeData.extraParams.schemaName,
        databaseName: treeNodeData.extraParams.databaseName,
        tableName: treeNodeData.originalTitle,
      },
    ];
    return aiDataCollectionService
      .deleteAiDataCollectionElement({
        id: treeNodeData.extraParams.aiDataCollectionId!,
        dataSourceId: treeNodeData.extraParams.dataSourceId!,
        elements,
      })
      .then(() => {
        const parentNode = getParentNode(treeNodeData.key, get().treeData);
        if (parentNode) {
          handleLoad(parentNode, {
            refresh: true,
          });
        } else {
          get().getTreeData();
        }
      });
  },
  refreshAiDataCollection: (dataSourceId) => {
    // Find the corresponding data source node through dataSourceId
    let dataSourceNode: any = null;
    get().treeData?.forEach((item) => {
      if (item.treeNodeType === TreeNodeType.DATA_SOURCE && item.extraParams.dataSourceId === dataSourceId) {
        dataSourceNode = item;
      }
      if (item.children && item.treeNodeType === TreeNodeType.GROUP) {
        item.children.forEach((child) => {
          if (child.treeNodeType === TreeNodeType.DATA_SOURCE && child.extraParams.dataSourceId === dataSourceId) {
            dataSourceNode = child;
          }
        });
      }
    });
    // Find the AI dataset below the data source node and refresh it.
    dataSourceNode?.children?.forEach((item: TreeNodeData) => {
      if (item.treeNodeType === TreeNodeType.AI_DATA_COLLECTIONS) {
        get().handleLoadData(item, {
          refresh: true,
        });
      }
    });
  },
  changeUserConfigTree: (type, value) => {
    set((state) => {
      return {
        userConfigTree: {
          ...state.userConfigTree,
          [type]: value,
        },
      };
    });
  },
  updateTreeNodeDataByKey: (key, getTreeNodeKeyParams) => {
    const newTreeData = get().treeData;
    const curNode = findNode(key, newTreeData);
    if (curNode && curNode.children !== undefined) {
      get().handleLoadData(curNode, {
        refresh: true,
      });
    } else {
      // If there is no curNode, it means that the user has not expanded the node, and call getChildren directly.
      if (getTreeNodeKeyParams) {
        const { treeNodeType, ...rest } = getTreeNodeKeyParams;
        treeConfig[treeNodeType].getChildren?.({
          ...rest,
          refresh: true,
        });
      }
    }
  },
  getTreeNodeKey: (props) => {
    const { treeNodeType, ...rest } = props;
    const key = treeConfig[treeNodeType].createTreeNodeKey?.(rest);
    return key || '';
  },
  updateTreeNodeDataByDetail: (props) => {
    const key = get().getTreeNodeKey(props);
    get().updateTreeNodeDataByKey(key, props);
  },
  refreshTreeNodeDataInBackground: async (props) => {
    const treeData = get().treeData;
    if (!treeData) {
      return;
    }

    const key = get().getTreeNodeKey(props);
    const node = findNode(key, treeData);
    if (!node) {
      return;
    }

    try {
      await get().handleLoadData(node, {
        refresh: true,
        closeExpandTreeNode: true,
        preserveInteraction: true,
      });
    } catch {
      // A background refresh must not turn a successful console save into an error.
    }
  },
  closeConnection: (dataSourceId) => {
    connectionService.closeConnection({ id: dataSourceId }).then(() => {
      get().setDataSourceRuntimeAvailability(dataSourceId, 'unavailable');
      const dataSourceKey = `dataSource_${dataSourceId}`;
      invalidateDataSourceTreeRequests(dataSourceId);
      if (get().currentLoadingTreeNode && isDataSourceTreeNodeKey(get().currentLoadingTreeNode!.key, dataSourceId)) {
        get().setCurrentLoadingTreeNode(null);
      }
      // Clear all child nodes under the current node and collapse the current node
      const newTreeData = get().treeData;
      const curNode = findNode(dataSourceKey, newTreeData);
      if (curNode && newTreeData) {
        curNode.children = undefined;
        const nextTreeData = [...newTreeData];
        const interactionState = reconcileTreeStateAfterRefresh(
          nextTreeData,
          get().selectedKeys,
          get().currentTreeNode,
          get().expandedKeys.filter((item) => item !== curNode.key),
          get().scrollTargetKey,
        );
        set({ treeData: nextTreeData, ...interactionState });
      }
    });
  },
  updateOriginalTitleByNodeId: (nodeKey, originalTitle) => {
    const newTreeData = get().treeData;
    const curNode = findNode(nodeKey, newTreeData);

    if (curNode && newTreeData) {
      curNode.originalTitle = originalTitle;
      get().setTreeData([...newTreeData!]);
    }
  },
  getChildrenByNodeId: (nodeId: string) => {
    const newTreeData = get().treeData;
    const curNode = findNode(nodeId, newTreeData);
    return curNode?.children || [];
  },
  initHiddenTreeNodeIds: () => {
    if (get().hiddenTreeNodeIds !== null) {
      return;
    }
    void hiddenTreeNodeStateCoordinator
      .initialize(
        () => dataSourceTreeService.getTreeHiddenTreeNodeIds(),
        (hiddenTreeNodeIds) => {
          if (get().hiddenTreeNodeIds === null) {
            set({ hiddenTreeNodeIds });
          }
        },
      )
      .catch(() => undefined);
  },
  addOrDeleteShowTreeNodeIds: (
    dataSourceId: number,
    changedKeys?: {
      add: (string | null | undefined)[];
      delete: (string | null | undefined)[];
    },
  ) => {
    const lifecycleVersion = treeStoreLifecycleVersion;
    const applyChanges = async () => {
      await hiddenTreeNodeStateCoordinator.initialize(
        () => dataSourceTreeService.getTreeHiddenTreeNodeIds(),
        (hiddenTreeNodeIds) => {
          if (get().hiddenTreeNodeIds === null) {
            set({ hiddenTreeNodeIds });
          }
        },
      );
      if (lifecycleVersion !== treeStoreLifecycleVersion) {
        return;
      }

      const hiddenTreeNodeIds = get().hiddenTreeNodeIds || {};
      const nextIds = applyHiddenTreeNodeChanges(hiddenTreeNodeIds[dataSourceId] || [], changedKeys);
      set({
        hiddenTreeNodeIds: {
          ...hiddenTreeNodeIds,
          [dataSourceId]: nextIds,
        },
      });
      await hiddenTreeNodeStateCoordinator.write(() =>
        dataSourceTreeService.updateHiddenTreeNodeIds(dataSourceId, nextIds),
      );
    };

    void applyChanges()
      .catch((error) => {
        console.error('Failed to persist hidden tree node settings', error);
      });
  },
});

const createStore: StateCreator<TreeStore, [['zustand/devtools', never]]> = (...parameters) => ({
  ...initTreeState,
  ...createTreeAction(...parameters),
});

type GlobalPersist = Pick<TreeStore, 'userConfigTree'>;

// local-storage Options
const persistOptions: PersistOptions<TreeStore, GlobalPersist> = {
  name: runtimeEditionConfig.treeStoreName,
  partialize: (state) => ({
    userConfigTree: state.userConfigTree,
  }),
};

export const useTreeStore = createWithEqualityFn<TreeStore>()(
  persist(
    devtools(createStore, {
      name: runtimeEditionConfig.treeStoreName,
    }),
    persistOptions,
  ),
  shallow,
);

// Clean store
export const clearTreeStore = () => {
  invalidateTreeRequests();
  useTreeStore.setState({
    ...initTreeState,
    userConfigTree: useTreeStore.getState().userConfigTree,
  });
};

export const getTreeStoreLifecycleVersion = () => treeStoreLifecycleVersion;
