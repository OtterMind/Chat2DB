import { openSchemaSyncModal } from '@/blocks/NewTree/functions/schemaSync';
import { ILoadDataOptions, normalizeTreeNodeLoadResult, treeConfig } from '@/blocks/NewTree/treeConfig';
import { TreeNodeType, initUserConfigTree } from '@/constants';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
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
  LatestTreeRefreshTracker,
  loadExistingTreeNodeRefresh,
  reconcileTreeInteractionAfterRefresh,
} from './backgroundRefresh';
import { loadNamespaceTree } from './loadNamespaceTree';
import { neatenDataSourceTreeNode, neatenDataSourcesList, neatenTreeData } from './utils';

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
  focusId: number | string | null;
  focusTreeNode: FocusTreeNode;
  editingTreeNode: TreeNodeData | null;
  currentTreeNode: TreeNodeData | null;
  currentLoadingTreeNode: TreeNodeData | null;
  dataSourceList: TreeNodeData[] | null;
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
  focusId: null,
  focusTreeNode: null,
  editingTreeNode: null,
  currentTreeNode: null,
  dataSourceList: null,
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
  getTreeData: (props?: { refresh?: boolean }) => Promise<void>;
  refreshTreeData: () => Promise<void>;
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
  handleLoadData: (nodeData: TreeNodeData, options?: ILoadDataOptions) => Promise<TreeNodeData[]>;
  setExpandedKeys: (expandedKeys: React.Key[]) => void;
  toggleExpandedKeys: (key: React.Key) => void;
  deleteDataSource: (dataSource: any) => Promise<void>;
  setSearchBarValue: (searchBarValue: string) => void;
  setSearchResultKeys: (searchResultKeys: TreeState['searchResultKeys']) => void;
  setSearchResult: (searchResult: any) => void;
  setCurrentLoadingTreeNode: (currentLoadingTreeNode: TreeNodeData | null) => void;
  getDataSourceList: (props?: { refresh?: boolean }) => void;
  generateDataSourceList: (data: TreeNodeData[]) => void;
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

const backgroundRefreshTracker = new LatestTreeRefreshTracker();

const updateTreeData = (
  list: TreeNodeData[],
  key: React.Key,
  children: TreeNodeData[],
  childCount?: number,
  clearChildCount = false,
): TreeNodeData[] => {
  return (
    list.map((node) => {
      if (node.key === key) {
        return {
          isLeaf: false,
          ...node,
          children: children || [],
          ...(clearChildCount ? { childCount: undefined } : childCount === undefined ? {} : { childCount }),
        };
      }
      if (node.children) {
        return {
          isLeaf: false,
          ...node,
          children: updateTreeData(node.children, key, children, childCount, clearChildCount),
        };
      }
      return node;
    }) || []
  );
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
    set(initTreeState);
  },
  refreshTreeData: async () => {
    await clearTreeStore();
    await get().getTreeData({ refresh: true });
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
  getTreeData: async (props) => {
    set({ treeData: null });
    get().initHiddenTreeNodeIds();
    await loadNamespaceTree(
      () => connectionService.getNamespaceList({ refresh: props?.refresh }),
      (res) => {
        const treeData = neatenTreeData(res);
        get().setTreeData(treeData);
        get().generateDataSourceList(treeData);
      },
    );
  },
  // Load data
  handleLoadData: (nodeData, config) => {
    const { refresh, closeExpandTreeNode } = config || {};
    return new Promise<any>((resolve, rj) => {
      const { key, children, treeNodeType, extraParams } = nodeData;
      if (children && !refresh) {
        resolve(children);
        return;
      }
      get().setCurrentLoadingTreeNode(nodeData);
      get().setCurrentTreeNode(nodeData);
      get().setSelectedKeys([nodeData.key]);

      const _treeData = get().treeData;
      const _expandedKeys = get().expandedKeys;
      if (_treeData && _expandedKeys && key) {
        set({ expandedKeys: removeSubkeys(_expandedKeys, _treeData, key) });
      }
      treeConfig[treeNodeType]
        .getChildren?.({ ...extraParams, refresh })
        .then((res) => {
          const loadResult = normalizeTreeNodeLoadResult(res);
          get().setCurrentLoadingTreeNode(null);
          // If it has already been expanded, it will not be expanded again.
          if (!get().expandedKeys.includes(key) && closeExpandTreeNode !== true) {
            get().setExpandedKeys([...get().expandedKeys, key]);
          }
          get().setTreeData((origin) => {
            return updateTreeData(origin, key, loadResult.children, loadResult.total);
          });
          resolve(loadResult.children);
        })
        .catch(() => {
          get().setTreeData((origin) => {
            return updateTreeData(origin, key, [], undefined, true);
          });
          get().toggleExpandedKeys(key);
          get().setCurrentLoadingTreeNode(null);
          rj();
        });
    });
  },
  createGroup: (parentId) => {
    const params = {
      name: 'New Group',
      parentId: parentId,
    };
    connectionService.createNamespace(params).then((res) => {
      const t = {
        id: res,
        name: params.name,
      };
      const newTreeData = get().treeData;

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
        findNode(`group_${parentId}`, newTreeData!)?.children?.push(newGroup);
      } else {
        newTreeData?.push(newGroup);
      }

      set({ treeData: [...newTreeData!] });
      get().setEditingTreeNode(newGroup);
      get().setSelectedKeys([`group_${t.id}`]);
      get().setScrollTargetKey(`group_${t.id}`);
    });
  },
  moveToGroup: (params) => {
    connectionService.updatePosition(params).then(() => {
      get().getTreeData();
    });
  },
  deleteGroup: (treeNodeData) => {
    return connectionService.deleteNamespace({ id: treeNodeData.id! }).then(() => {
      get().getTreeData();
    });
  },
  setTreeData: (treeData) => {
    if (typeof treeData === 'function') {
      const _treeData = treeData(get().treeData);
      set({ treeData: _treeData });
      if (get().searchBarValue && _treeData) {
        const visibleTreeData = filterTreeNodesForDisplay(_treeData, {
          hiddenTreeNodeIds: get().hiddenTreeNodeIds,
          aiDataCollectionEnabled: runtimeEditionConfig.aiDataCollection,
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
          aiDataCollectionEnabled: runtimeEditionConfig.aiDataCollection,
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
    const newTreeData = get().treeData;
    const newDataSource = neatenDataSourceTreeNode(dataSource)!;
    if (!newTreeData) return;

    if (dataSource.spaceId) {
      const groupId = `group_${dataSource.spaceId}`;
      findNode(groupId, newTreeData!)?.children?.push(newDataSource);
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
          const newTreeData = get().treeData;

          const newTreeDataAfterDelete: any = [];
          newTreeData?.forEach((item) => {
            if (item.treeNodeType === TreeNodeType.GROUP) {
              const newChildren = item.children?.filter((child) => child.key !== `dataSource_${dataSource.id}`);
              newTreeDataAfterDelete.push({
                ...item,
                children: newChildren,
              });
            } else {
              if (item.key !== `dataSource_${dataSource.id}`) {
                newTreeDataAfterDelete.push(item);
              }
            }
          });
          set({
            treeData: newTreeDataAfterDelete,
            currentTreeNode: null,
          });
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
    const newTreeData = get().treeData;
    const parentNode = getParentNode(`dataSource_${dataSourceDetails.id}`, newTreeData!);
    const newTreeNode = neatenDataSourceTreeNode(dataSourceDetails);
    if (!newTreeNode) return;
    // If it is a data source under group
    if (parentNode) {
      const index = parentNode.children?.findIndex((item) => item.key === `dataSource_${dataSourceDetails.id}`);
      parentNode.children?.splice(index!, 1, newTreeNode);
    } else {
      const index = newTreeData?.findIndex((item) => item.key === `dataSource_${dataSourceDetails.id}`);
      newTreeData?.splice(index!, 1, newTreeNode);
    }
    // If it was originally expanded and needs to be collapsed
    set({
      expandedKeys: get().expandedKeys.filter((item) => item !== newTreeNode.key),
      treeData: [...newTreeData!],
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
    const dataSourceList: TreeNodeData[] = [];
    function collectDataSources(node) {
      if (node.treeNodeType === TreeNodeType.DATA_SOURCE) {
        dataSourceList.push(node);
      }
      if (node.children) {
        node.children.forEach((child) => {
          collectDataSources(child);
        });
      }
    }

    treeData.forEach((item) => {
      collectDataSources(item);
    });

    set({ dataSourceList });
  },
  deleteAiDataCollection: (treeNodeData, handleLoad) => {
    return aiDataCollectionService.deleteAiDataCollection({ id: treeNodeData.id! }).then(() => {
      const parentNode = getParentNode(treeNodeData.key, get().treeData!);
      handleLoad(parentNode, {
        refresh: true,
      });

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
        const parentNode = getParentNode(treeNodeData.key, get().treeData!);
        handleLoad(parentNode, {
          refresh: true,
        });
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
    const curNode = findNode(key, newTreeData!);
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

    const { treeNodeType, ...rest } = props;
    const getChildren = treeConfig[treeNodeType].getChildren;
    if (!getChildren) {
      return;
    }

    const requestSequence = backgroundRefreshTracker.begin(key);

    try {
      const loadResult = await loadExistingTreeNodeRefresh(treeData, key, () =>
        getChildren({
          ...rest,
          refresh: true,
        }),
      );
      if (!loadResult) {
        return;
      }
      if (!backgroundRefreshTracker.isLatest(key, requestSequence)) {
        return;
      }

      set((state) => {
        if (!state.treeData) {
          return {};
        }
        const refreshedTreeData = applyExistingTreeNodeRefresh(state.treeData, key, loadResult);
        if (refreshedTreeData === state.treeData) {
          return {};
        }
        const interactionState = reconcileTreeInteractionAfterRefresh(
          refreshedTreeData,
          state.selectedKeys,
          state.currentTreeNode,
        );
        return {
          treeData: refreshedTreeData,
          ...interactionState,
        };
      });
    } catch {
      // A background refresh must not turn a successful console save into an error.
    } finally {
      backgroundRefreshTracker.finish(key, requestSequence);
    }
  },
  closeConnection: (dataSourceId) => {
    connectionService.closeConnection({ id: dataSourceId }).then(() => {
      // Clear all child nodes under the current node and collapse the current node
      const newTreeData = get().treeData;
      const curNode = findNode(`dataSource_${dataSourceId}`, newTreeData!);
      if (curNode) {
        curNode.children = undefined;
        get().setTreeData([...newTreeData!]);
        const expandedKeys = get().expandedKeys || [];
        const key = curNode.key;
        if (expandedKeys.includes(key)) {
          set({ expandedKeys: expandedKeys.filter((item) => item !== key) });
        }
      }
    });
  },
  updateOriginalTitleByNodeId: (nodeKey, originalTitle) => {
    const newTreeData = get().treeData;
    const curNode = findNode(nodeKey, newTreeData!);

    if (curNode) {
      curNode.originalTitle = originalTitle;
      get().setTreeData([...newTreeData!]);
    }
  },
  getChildrenByNodeId: (nodeId: string) => {
    const newTreeData = get().treeData;
    const curNode = findNode(nodeId, newTreeData!);
    return curNode?.children || [];
  },
  initHiddenTreeNodeIds: () => {
    dataSourceTreeService.getTreeHiddenTreeNodeIds().then((res) => {
      set({
        hiddenTreeNodeIds: res,
      });
    });
  },
  addOrDeleteShowTreeNodeIds: (
    dataSourceId: number,
    changedKeys?: {
      add: (string | null | undefined)[];
      delete: (string | null | undefined)[];
    },
  ) => {
    const _hiddenTreeNodeIds = get().hiddenTreeNodeIds || {};
    if (!_hiddenTreeNodeIds[dataSourceId]) {
      _hiddenTreeNodeIds[dataSourceId] = [];
    }

    if (changedKeys) {
      // Add new node ID
      if (changedKeys.add.length > 0) {
        const validAddKeys = changedKeys.add.filter((key) => key != null) as string[];
        _hiddenTreeNodeIds[dataSourceId].push(...validAddKeys);
      }

      // Delete node ID
      if (changedKeys.delete.length > 0) {
        const validDeleteKeys = changedKeys.delete.filter((key) => key != null) as string[];
        _hiddenTreeNodeIds[dataSourceId] = _hiddenTreeNodeIds[dataSourceId].filter(
          (id) => !validDeleteKeys.includes(id),
        );
      }
    }

    // Update status and save to database
    set({ hiddenTreeNodeIds: { ..._hiddenTreeNodeIds } });
    dataSourceTreeService.updateHiddenTreeNodeIds(dataSourceId, _hiddenTreeNodeIds[dataSourceId]);
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
  useTreeStore.setState({
    ...initTreeState,
    userConfigTree: useTreeStore.getState().userConfigTree,
  });
};
