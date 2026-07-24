import React, { memo, useEffect, useMemo, Fragment, useState } from 'react';
import styles from './index.less';
import i18n from '@/i18n';
import { Button } from 'antd';
import { staticMessage } from '@chat2db/ui';
import SplitPane from 'react-split-pane';
import {
  DndContext,
  PointerSensor,
  pointerWithin,
  type CollisionDetection,
  type DragEndEvent,
  type DragStartEvent,
  useSensor,
  useSensors,
} from '@dnd-kit/core';

// ----- constants -----
import { ConsoleOpenedStatus, WorkspaceTabType, workspaceTabConfig } from '@/constants';
import {
  IWorkspaceTab,
  IWorkspaceTabPaneNode,
  IWorkspaceTabSplitLayout,
  WorkspaceTabPaneId,
  WorkspaceTabSplitDirection,
} from '@/typings';

// ----- components -----
import CustomTabs, { ITabContextActions, ITabItem } from '@/components/Tabs';
import ViewTable from '@/components/ViewTable';
import DatabaseTableEditor from '@/blocks/DatabaseTableEditor';
import SQLExecute from '../SQLExecute';
import NewViewAllTable from '../NewViewAllTable';
import WorkspaceRightEmpty from '../WorkspaceRightEmpty';
import ChangeAiTableInfo from '@/components/ChangeAiTableInfo';
import RedisAllData from '@/blocks/RedisAllData';
import Iconfont from '@/components/Iconfont';
import { useZoerStore } from '@/store/zoer';
import AccountPrivilegePanel from '../AccountPrivilegePanel';
import ContentDiffTab from './ContentDiffTab';

// ---- store -----
import { useWorkspaceStore } from '@/store/workspace';
import { isWorkspaceResultInspectorCode } from '@/store/workspace/utils/resultInspector';
import { useTreeStore } from '@/store/tree';

// ----- services -----
import historyService from '@/service/history';
import sqlService from '@/service/sql';

import { copyToClipboard, getTemporaryId, isTemporaryId } from '@/utils';

import { useIndexDBStore } from '@/store/indexDB';
import { getDatabaseSupport } from '@/utils/database';
import ConsoleERModal from '@/blocks/ERModal/ConsoleERModal';
import { getLocalTextFileIcon, SQL_FILE_EXTENSION_NAME } from '../../utils/localTextFile';
import { EditorType } from '@/components/SQLEditor';

const SplitPaneAny = SplitPane as any;
const MAIN_WORKSPACE_TAB_PANE: WorkspaceTabPaneId = 'main';
const SPLIT_WORKSPACE_TAB_PANE: WorkspaceTabPaneId = 'split';
const WORKSPACE_TAB_PANE_DROPPABLE_PREFIX = 'workspace-tab-pane:';
type WorkspaceTabSplitNodePath = Array<'first' | 'second'>;

function getWorkspaceTabPaneDroppableId(paneId: WorkspaceTabPaneId) {
  return `${WORKSPACE_TAB_PANE_DROPPABLE_PREFIX}${paneId}`;
}

function getWorkspaceTabPaneIdFromDroppableId(id: string): WorkspaceTabPaneId | undefined {
  const paneId = id.replace(WORKSPACE_TAB_PANE_DROPPABLE_PREFIX, '') as WorkspaceTabPaneId;
  if (id.startsWith(WORKSPACE_TAB_PANE_DROPPABLE_PREFIX) && paneId) {
    return paneId;
  }
  return undefined;
}

function createWorkspaceTabPaneId() {
  return `pane_${Date.now()}_${Math.random().toString(36)
.slice(2, 8)}`;
}

function createPaneNode(id: WorkspaceTabPaneId): IWorkspaceTabPaneNode {
  return {
    type: 'pane',
    id,
  };
}

function createDefaultSplitRoot(direction: WorkspaceTabSplitDirection): IWorkspaceTabPaneNode {
  return {
    type: 'split',
    direction,
    first: createPaneNode(MAIN_WORKSPACE_TAB_PANE),
    second: createPaneNode(SPLIT_WORKSPACE_TAB_PANE),
  };
}

function collectWorkspaceTabPaneIds(node?: IWorkspaceTabPaneNode | null): WorkspaceTabPaneId[] {
  if (!node) {
    return [];
  }
  if (node.type === 'pane') {
    return [node.id];
  }
  return [...collectWorkspaceTabPaneIds(node.first), ...collectWorkspaceTabPaneIds(node.second)];
}

function findWorkspaceTabPaneNode(node: IWorkspaceTabPaneNode | undefined, paneId: WorkspaceTabPaneId) {
  if (!node) {
    return false;
  }
  if (node.type === 'pane') {
    return node.id === paneId;
  }
  return findWorkspaceTabPaneNode(node.first, paneId) || findWorkspaceTabPaneNode(node.second, paneId);
}

function replaceWorkspaceTabPaneNode(
  node: IWorkspaceTabPaneNode,
  paneId: WorkspaceTabPaneId,
  replacement: IWorkspaceTabPaneNode,
): IWorkspaceTabPaneNode {
  if (node.type === 'pane') {
    return node.id === paneId ? replacement : node;
  }
  return {
    ...node,
    first: replaceWorkspaceTabPaneNode(node.first, paneId, replacement),
    second: replaceWorkspaceTabPaneNode(node.second, paneId, replacement),
  };
}

function updateWorkspaceTabSplitNodeSize(
  node: IWorkspaceTabPaneNode,
  path: WorkspaceTabSplitNodePath,
  size: number | string,
): IWorkspaceTabPaneNode {
  if (node.type === 'pane') {
    return node;
  }

  if (!path.length) {
    return {
      ...node,
      size,
    };
  }

  const [nextPathItem, ...restPath] = path;
  return nextPathItem === 'first'
    ? {
        ...node,
        first: updateWorkspaceTabSplitNodeSize(node.first, restPath, size),
      }
    : {
        ...node,
        second: updateWorkspaceTabSplitNodeSize(node.second, restPath, size),
      };
}

function pruneWorkspaceTabPaneNode(
  node: IWorkspaceTabPaneNode,
  validPaneIds: Set<WorkspaceTabPaneId>,
): IWorkspaceTabPaneNode | null {
  if (node.type === 'pane') {
    return validPaneIds.has(node.id) ? node : null;
  }
  const first = pruneWorkspaceTabPaneNode(node.first, validPaneIds);
  const second = pruneWorkspaceTabPaneNode(node.second, validPaneIds);
  if (first && second) {
    return {
      ...node,
      first,
      second,
    };
  }
  return first || second;
}

function getSnapshotDDL(uniqueData: IWorkspaceTab['uniqueData']) {
  return Promise.resolve(uniqueData?.ddl || '');
}

function rebuildSqlExecuteTabData(item: IWorkspaceTab) {
  const uniqueData = item.uniqueData;
  if (!uniqueData) {
    return uniqueData;
  }

  if (uniqueData.loadSQL) {
    return uniqueData;
  }

  const { dataSourceId, databaseName, schemaName } = uniqueData;

  if (item.type === WorkspaceTabType.VIEW) {
    const tableName = uniqueData.viewName || uniqueData.tableName || item.title?.replace(/\[.*\]$/, '');
    return {
      ...uniqueData,
      viewName: uniqueData.viewName || tableName,
      tableName,
      loadSQL: () => {
        if (!dataSourceId || !databaseName || !tableName) {
          return getSnapshotDDL(uniqueData);
        }
        return sqlService
          .getViewDetail({
            dataSourceId: dataSourceId!,
            databaseType: uniqueData.databaseType!,
            databaseName: databaseName!,
            schemaName,
            tableName: tableName!,
          } as any)
          .then((res) => res.ddl);
      },
    };
  }

  if (item.type === WorkspaceTabType.FUNCTION) {
    const functionName = uniqueData.functionName || item.title?.replace(/\[.*\]$/, '');
    return {
      ...uniqueData,
      functionName,
      loadSQL: () => {
        if (!dataSourceId || !databaseName || !functionName) {
          return getSnapshotDDL(uniqueData);
        }
        return sqlService
          .getFunctionDetail({
            dataSourceId: dataSourceId!,
            databaseType: uniqueData.databaseType!,
            databaseName: databaseName!,
            schemaName,
            functionName: functionName!,
          } as any)
          .then((res) => res.functionBody);
      },
    };
  }

  if (item.type === WorkspaceTabType.PROCEDURE) {
    const procedureName = uniqueData.procedureName || item.title?.replace(/\[.*\]$/, '');
    return {
      ...uniqueData,
      procedureName,
      loadSQL: () => {
        if (!dataSourceId || !databaseName || !procedureName) {
          return getSnapshotDDL(uniqueData);
        }
        return sqlService
          .getProcedureDetail({
            dataSourceId: dataSourceId!,
            databaseType: uniqueData.databaseType!,
            databaseName: databaseName!,
            schemaName,
            procedureName: procedureName!,
          } as any)
          .then((res) => res.procedureBody);
      },
    };
  }

  if (item.type === WorkspaceTabType.TRIGGER) {
    const triggerName = uniqueData.triggerName || item.title?.replace(/\[.*\]$/, '');
    return {
      ...uniqueData,
      triggerName,
      loadSQL: () => {
        if (!dataSourceId || !databaseName || !triggerName) {
          return getSnapshotDDL(uniqueData);
        }
        return sqlService
          .getTriggerDetail({
            dataSourceId: dataSourceId!,
            databaseType: uniqueData.databaseType!,
            databaseName: databaseName!,
            schemaName,
            triggerName: triggerName!,
          } as any)
          .then((res) => res.triggerBody);
      },
    };
  }

  return uniqueData;
}

const RECENTLY_CLOSED_WORKSPACE_TAB_LIMIT = 20;

function isSavedConsoleLikeWorkspaceTab(item?: IWorkspaceTab | null) {
  if (!item) {
    return false;
  }
  return (
    item.type === WorkspaceTabType.CONSOLE ||
    item.type === WorkspaceTabType.FUNCTION ||
    item.type === WorkspaceTabType.PROCEDURE ||
    item.type === WorkspaceTabType.TRIGGER ||
    item.type === WorkspaceTabType.VIEW ||
    // Accept table and missing item.type for backward compatibility.
    item.type === ('table' as any) ||
    !item.type
  );
}

function getWorkspaceTabReference(item: IWorkspaceTab) {
  const uniqueData = item.uniqueData || {};
  const lines = [
    `Tab: ${item.title}`,
    `Type: ${item.type || WorkspaceTabType.CONSOLE}`,
    uniqueData.dataSourceName ? `DataSource: ${uniqueData.dataSourceName}` : '',
    uniqueData.dataSourceId ? `DataSourceId: ${uniqueData.dataSourceId}` : '',
    uniqueData.databaseName ? `Database: ${uniqueData.databaseName}` : '',
    uniqueData.schemaName ? `Schema: ${uniqueData.schemaName}` : '',
    uniqueData.tableName ? `Table: ${uniqueData.tableName}` : '',
    uniqueData.viewName ? `View: ${uniqueData.viewName}` : '',
    uniqueData.functionName ? `Function: ${uniqueData.functionName}` : '',
    uniqueData.procedureName ? `Procedure: ${uniqueData.procedureName}` : '',
    uniqueData.triggerName ? `Trigger: ${uniqueData.triggerName}` : '',
    uniqueData.filePath ? `File: ${uniqueData.filePath}` : '',
    uniqueData.consoleId ? `ConsoleId: ${uniqueData.consoleId}` : '',
    `WorkspaceTabId: ${item.id}`,
  ];
  return lines.filter(Boolean).join('\n');
}

function createWorkspaceTabSnapshot(
  item: IWorkspaceTab,
  ddl?: string,
  options: { stripFunctions?: boolean } = {},
): IWorkspaceTab {
  const { stripFunctions = true } = options;
  const uniqueData = item.uniqueData
    ? stripFunctions
      ? Object.fromEntries(Object.entries(item.uniqueData).filter(([, value]) => typeof value !== 'function'))
      : {
          ...item.uniqueData,
        }
    : undefined;
  if (uniqueData && ddl !== undefined) {
    uniqueData.ddl = ddl;
  }
  return {
    ...item,
    pinned: false,
    uniqueData,
  };
}

function createPersistableWorkspaceTabSnapshot(item: IWorkspaceTab, ddl?: string): IWorkspaceTab | null {
  if (hasFunctionValue(item.uniqueData)) {
    return null;
  }
  return createWorkspaceTabSnapshot(item, ddl, { stripFunctions: true });
}

function orderPinnedWorkspaceTabsFirst(tabs: IWorkspaceTab[]) {
  return [...tabs.filter((tab) => tab.pinned), ...tabs.filter((tab) => !tab.pinned)];
}

function createTemporaryWorkspaceTabCopy(
  item: IWorkspaceTab,
  ddl: string | undefined,
  idPrefix: string,
  title: string,
) {
  const snapshot = createWorkspaceTabSnapshot(item, ddl, { stripFunctions: false });
  return {
    ...snapshot,
    id: getTemporaryId(`${idPrefix}_${item.id}_${Date.now()}`),
    title,
    pinned: false,
    uniqueData: {
      ...snapshot.uniqueData,
      consoleId: undefined,
    },
  };
}

function hasFunctionValue(value: unknown): boolean {
  if (typeof value === 'function') {
    return true;
  }
  if (!value || typeof value !== 'object') {
    return false;
  }
  if (Array.isArray(value)) {
    return value.some(hasFunctionValue);
  }
  return Object.values(value as Record<string, unknown>).some(hasFunctionValue);
}

function canCopyWorkspaceTab(item?: IWorkspaceTab | null) {
  if (!item) {
    return false;
  }
  return !hasFunctionValue(item.uniqueData);
}

function getWorkspaceTabMap(workspaceTabList: IWorkspaceTab[]) {
  return new Map<string | number, IWorkspaceTab>(workspaceTabList.map((item) => [item.id, item]));
}

function getValidOrderedPaneTabIds(
  ids: Array<string | number> = [],
  workspaceTabMap: Map<string | number, IWorkspaceTab>,
) {
  const dedupedIds: Array<string | number> = [];
  ids.forEach((id) => {
    if (workspaceTabMap.has(id) && !dedupedIds.includes(id)) {
      dedupedIds.push(id);
    }
  });
  return dedupedIds;
}

function getActivePaneTabId(ids: Array<string | number>, activeId?: string | number | null) {
  if (activeId !== undefined && activeId !== null && ids.includes(activeId)) {
    return activeId;
  }
  return ids[0] ?? null;
}

function getPaneIdForTab(
  layout: IWorkspaceTabSplitLayout | null | undefined,
  tabId: string | number,
): WorkspaceTabPaneId {
  if (layout) {
    const paneId = Object.keys(layout.paneTabIds || {}).find((id) => layout.paneTabIds[id]?.includes(tabId));
    if (paneId) {
      return paneId;
    }
  }
  return MAIN_WORKSPACE_TAB_PANE;
}

function normalizeWorkspaceTabSplitLayout(
  layout: IWorkspaceTabSplitLayout | null | undefined,
  workspaceTabList: IWorkspaceTab[],
  activeConsoleId?: string | number | null,
) {
  if (!layout || !workspaceTabList.length) {
    return null;
  }

  const workspaceTabMap = getWorkspaceTabMap(workspaceTabList);
  const assignedIds = new Set<string | number>();
  const root = layout.root || createDefaultSplitRoot(layout.direction || 'vertical');
  const paneIdsFromRoot = collectWorkspaceTabPaneIds(root);
  const paneIds = paneIdsFromRoot.length ? paneIdsFromRoot : [MAIN_WORKSPACE_TAB_PANE, SPLIT_WORKSPACE_TAB_PANE];
  const nextPaneTabIds = paneIds.reduce(
    (result, paneId) => {
      result[paneId] = getValidOrderedPaneTabIds(layout.paneTabIds?.[paneId], workspaceTabMap).filter((id) => {
        if (assignedIds.has(id)) {
          return false;
        }
        assignedIds.add(id);
        return true;
      });
      return result;
    },
    {} as Record<WorkspaceTabPaneId, Array<string | number>>,
  );
  const activePane = layout.activePane || MAIN_WORKSPACE_TAB_PANE;
  const targetPane = paneIds.includes(activePane) ? activePane : paneIds[0] || MAIN_WORKSPACE_TAB_PANE;
  const targetIds = nextPaneTabIds[targetPane] || [];

  workspaceTabList.forEach((item) => {
    if (!assignedIds.has(item.id)) {
      targetIds.push(item.id);
      assignedIds.add(item.id);
    }
  });

  const validPaneIds = new Set(
    paneIds.filter((paneId) => {
      return !!nextPaneTabIds[paneId]?.length;
    }),
  );
  const normalizedRoot = pruneWorkspaceTabPaneNode(root, validPaneIds);
  if (!normalizedRoot || normalizedRoot.type === 'pane') {
    return null;
  }

  const normalizedPaneIds = collectWorkspaceTabPaneIds(normalizedRoot);
  const normalizedPaneTabIds = normalizedPaneIds.reduce(
    (result, paneId) => {
      result[paneId] = nextPaneTabIds[paneId] || [];
      return result;
    },
    {} as Record<WorkspaceTabPaneId, Array<string | number>>,
  );

  const paneIdWithActiveConsole = normalizedPaneIds.find((paneId) =>
    normalizedPaneTabIds[paneId]?.includes(activeConsoleId as any),
  );
  const fallbackActivePane = normalizedPaneIds.includes(targetPane) ? targetPane : normalizedPaneIds[0];
  const normalizedActivePane = paneIdWithActiveConsole || fallbackActivePane;
  const normalizedActivePaneIds =
    activeConsoleId !== undefined && activeConsoleId !== null
      ? normalizedPaneIds.reduce(
          (result, paneId) => {
            result[paneId] =
              paneId === normalizedActivePane && normalizedPaneTabIds[paneId]?.includes(activeConsoleId)
                ? activeConsoleId
                : layout.activeTabIds?.[paneId];
            return result;
          },
          {} as Partial<Record<WorkspaceTabPaneId, number | string | null>>,
        )
      : layout.activeTabIds || {};

  return {
    direction: normalizedRoot.direction,
    root: normalizedRoot,
    activePane: normalizedActivePane,
    paneTabIds: normalizedPaneTabIds,
    activeTabIds: normalizedPaneIds.reduce(
      (result, paneId) => {
        result[paneId] = getActivePaneTabId(normalizedPaneTabIds[paneId] || [], normalizedActivePaneIds[paneId]);
        return result;
      },
      {} as Partial<Record<WorkspaceTabPaneId, number | string | null>>,
    ),
  } as IWorkspaceTabSplitLayout;
}

function areWorkspaceTabSplitLayoutsEqual(
  a: IWorkspaceTabSplitLayout | null | undefined,
  b: IWorkspaceTabSplitLayout | null | undefined,
) {
  return JSON.stringify(a || null) === JSON.stringify(b || null);
}

function getWorkspaceTabListByPane(
  workspaceTabList: IWorkspaceTab[],
  layout: IWorkspaceTabSplitLayout | null,
  paneId: WorkspaceTabPaneId,
) {
  if (!layout) {
    return paneId === MAIN_WORKSPACE_TAB_PANE ? workspaceTabList : [];
  }
  const workspaceTabMap = getWorkspaceTabMap(workspaceTabList);
  return (layout.paneTabIds[paneId] || []).map((id) => workspaceTabMap.get(id)).filter(Boolean) as IWorkspaceTab[];
}

function orderSplitLayoutPaneIdsByPinned(layout: IWorkspaceTabSplitLayout | null, workspaceTabList: IWorkspaceTab[]) {
  if (!layout) {
    return null;
  }
  const workspaceTabMap = getWorkspaceTabMap(workspaceTabList);
  const paneIds = collectWorkspaceTabPaneIds(layout.root || createDefaultSplitRoot(layout.direction || 'vertical'));
  return {
    ...layout,
    paneTabIds: paneIds.reduce(
      (result, paneId) => {
        result[paneId] = orderPinnedWorkspaceTabsFirst(getWorkspaceTabListByPane(workspaceTabList, layout, paneId))
          .map((item) => item.id)
          .filter((id) => workspaceTabMap.has(id));
        return result;
      },
      {} as Record<WorkspaceTabPaneId, Array<string | number>>,
    ),
  };
}

function getWorkspaceTabIdsByLayout(layout: IWorkspaceTabSplitLayout) {
  const paneIds = collectWorkspaceTabPaneIds(layout.root || createDefaultSplitRoot(layout.direction || 'vertical'));
  return paneIds.flatMap((paneId) => layout.paneTabIds[paneId] || []);
}

function getNextActiveWorkspaceTabIdAfterClose(params: {
  activeConsoleId?: string | number | null;
  closeTabIds: Set<string | number>;
  layout: IWorkspaceTabSplitLayout | null | undefined;
  orderedNextWorkspaceTabList: IWorkspaceTab[];
}) {
  const { activeConsoleId, closeTabIds, layout, orderedNextWorkspaceTabList } = params;
  if (activeConsoleId === undefined || activeConsoleId === null || !closeTabIds.has(activeConsoleId)) {
    return activeConsoleId ?? null;
  }

  if (!orderedNextWorkspaceTabList.length) {
    return null;
  }

  const workspaceTabMap = getWorkspaceTabMap(orderedNextWorkspaceTabList);
  if (layout) {
    const activePaneId = getPaneIdForTab(layout, activeConsoleId);
    const paneTabIds = layout.paneTabIds[activePaneId] || [];
    const activeIndex = paneTabIds.findIndex((id) => id === activeConsoleId);
    const isAvailableTabId = (id: string | number) => !closeTabIds.has(id) && workspaceTabMap.has(id);
    const previousTabId = paneTabIds.slice(0, Math.max(activeIndex, 0)).reverse()
.find(isAvailableTabId);
    const nextTabId = paneTabIds.slice(activeIndex + 1).find(isAvailableTabId);
    const fallbackPaneTabId = paneTabIds.find(isAvailableTabId);

    if (previousTabId !== undefined) {
      return previousTabId;
    }
    if (nextTabId !== undefined) {
      return nextTabId;
    }
    if (fallbackPaneTabId !== undefined) {
      return fallbackPaneTabId;
    }
  }

  return orderedNextWorkspaceTabList[orderedNextWorkspaceTabList.length - 1]?.id ?? null;
}

function getWorkspaceTabIdFromDndId(id: string, workspaceTabList: IWorkspaceTab[]) {
  return workspaceTabList.find((item) => String(item.id) === id)?.id;
}

const workspaceTabCollisionDetection: CollisionDetection = (args) => {
  const collisions = pointerWithin(args);
  const paneCollisions = collisions.filter(({ id }) => getWorkspaceTabPaneIdFromDroppableId(String(id)));
  const tabCollisions = collisions.filter(({ id }) => !getWorkspaceTabPaneIdFromDroppableId(String(id)));
  return tabCollisions.length ? tabCollisions : paneCollisions.length ? paneCollisions : collisions;
};

const WorkspaceTabs = memo(() => {
  const {
    activeConsoleId,
    consoleList,
    workspaceTabList,
    workspaceTabSplitLayout,
    recentlyClosedWorkspaceTabs,
    editorList,
    getOpenConsoleList,
    setActiveConsoleId,
    setWorkspaceTabList,
    createConsole,
  } = useWorkspaceStore((state) => {
    return {
      consoleList: state.consoleList,
      activeConsoleId: state.activeConsoleId,
      workspaceTabList: state.workspaceTabList,
      workspaceTabSplitLayout: state.workspaceTabSplitLayout,
      recentlyClosedWorkspaceTabs: state.recentlyClosedWorkspaceTabs,
      editorList: state.editorList,
      getOpenConsoleList: state.getOpenConsoleList,
      setActiveConsoleId: state.setActiveConsoleId,
      setWorkspaceTabList: state.setWorkspaceTabList,
      createConsole: state.createConsole,
    };
  });

  // Get the currently selected data source.
  const { zoerBoundInfo } = useZoerStore((state) => {
    return {
      zoerBoundInfo: state.zoerBoundInfo,
    };
  });

  const { currentTreeNode, dataSourceList } = useTreeStore((state) => {
    return {
      currentTreeNode: state.currentTreeNode,
      dataSourceList: state.dataSourceList,
    };
  });

  const canCreateConsole = useMemo(() => {
    return !!currentTreeNode?.extraParams?.dataSourceId || !!dataSourceList?.length;
  }, [currentTreeNode, dataSourceList]);

  const indexedDB = useIndexDBStore((state) => {
    return {
      deleteValue: state.deleteValue,
    };
  });
  const [draggingWorkspaceTabKey, setDraggingWorkspaceTabKey] = useState<string | undefined>();
  const splitTabDragSensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  // Get the console.
  useEffect(() => {
    getOpenConsoleList();
  }, []);

  const setWorkspaceTabsState = (
    tabs: IWorkspaceTab[],
    layout: IWorkspaceTabSplitLayout | null | undefined = useWorkspaceStore.getState().workspaceTabSplitLayout,
    nextActiveConsoleId: string | number | null | undefined = useWorkspaceStore.getState().activeConsoleId,
  ) => {
    const orderedTabs = orderPinnedWorkspaceTabsFirst(tabs);
    const orderedLayout = orderSplitLayoutPaneIdsByPinned(layout || null, orderedTabs);
    const normalizedLayout = normalizeWorkspaceTabSplitLayout(orderedLayout, orderedTabs, nextActiveConsoleId);
    setWorkspaceTabList(orderedTabs);
    if (!areWorkspaceTabSplitLayoutsEqual(useWorkspaceStore.getState().workspaceTabSplitLayout, normalizedLayout)) {
      useWorkspaceStore.setState({
        workspaceTabSplitLayout: normalizedLayout,
      });
    }
  };

  const updateWorkspaceTabSplitLayout = (layout: IWorkspaceTabSplitLayout | null | undefined) => {
    const normalizedLayout = normalizeWorkspaceTabSplitLayout(
      layout,
      useWorkspaceStore.getState().workspaceTabList || [],
      useWorkspaceStore.getState().activeConsoleId,
    );
    useWorkspaceStore.setState({
      workspaceTabSplitLayout: normalizedLayout,
    });
  };

  useEffect(() => {
    const normalizedLayout = normalizeWorkspaceTabSplitLayout(
      workspaceTabSplitLayout,
      workspaceTabList || [],
      activeConsoleId,
    );
    if (!areWorkspaceTabSplitLayoutsEqual(workspaceTabSplitLayout, normalizedLayout)) {
      useWorkspaceStore.setState({
        workspaceTabSplitLayout: normalizedLayout,
      });
    }
  }, [workspaceTabList, workspaceTabSplitLayout, activeConsoleId]);

  useEffect(() => {
    const workspaceStore = useWorkspaceStore.getState();
    if (isWorkspaceResultInspectorCode(workspaceStore.currentWorkspaceExtend)) {
      workspaceStore.setCurrentWorkspaceExtend(null);
    }
  }, [activeConsoleId]);

  // Convert consoleList to the shared workspaceTabList format first.
  useEffect(() => {
    if (!consoleList) {
      return;
    }

    let openConsoleWorkspaceTabItems = consoleList.map((item) => {
      return {
        id: item.id,
        type: item.operationType,
        title: item.name,
        uniqueData: {
          consoleId: item.id,
          dataSourceId: item.dataSourceId,
          dataSourceName: item.dataSourceName,
          databaseType: item.type,
          databaseName: item.databaseName,
          schemaName: item.schemaName,
          status: item.status,
          ddl: item.ddl,
          connectable: item.connectable,
          popoverContent: item.popoverContent,
        },
      };
    });

    openConsoleWorkspaceTabItems = openConsoleWorkspaceTabItems.filter((item) => {
      if (zoerBoundInfo && item.type === WorkspaceTabType.CONSOLE) {
        return false;
      }
      return true;
    });

    const openConsoleTabMap = new Map<string | number, IWorkspaceTab>(
      openConsoleWorkspaceTabItems.map((item) => [item.id, item]),
    );
    const currentWorkspaceTabList = useWorkspaceStore.getState().workspaceTabList || [];
    const restoredWorkspaceTabList = zoerBoundInfo
      ? currentWorkspaceTabList.filter((item) => {
          const consoleId = item.uniqueData?.consoleId ?? item.id;
          return item.type !== WorkspaceTabType.CONSOLE || typeof consoleId !== 'number';
        })
      : currentWorkspaceTabList;
    const nextWorkspaceTabList = restoredWorkspaceTabList.length
      ? restoredWorkspaceTabList.map((item) => {
          const openConsoleTab = openConsoleTabMap.get(item.id);
          if (openConsoleTab) {
            openConsoleTabMap.delete(item.id);
            return {
              ...openConsoleTab,
              pinned: item.pinned,
            };
          }
          return item;
        })
      : [];
    nextWorkspaceTabList.push(...openConsoleTabMap.values());

    const orderedWorkspaceTabList = orderPinnedWorkspaceTabsFirst(nextWorkspaceTabList);
    const currentActiveConsoleId = useWorkspaceStore.getState().activeConsoleId;
    setWorkspaceTabsState(
      orderedWorkspaceTabList,
      useWorkspaceStore.getState().workspaceTabSplitLayout,
      currentActiveConsoleId,
    );
    if (!orderedWorkspaceTabList.some((item) => item.id === currentActiveConsoleId)) {
      setActiveConsoleId(orderedWorkspaceTabList[0]?.id ?? null);
    }
  }, [consoleList, zoerBoundInfo]);

  // Close a tab.
  const closeWindowTab = (key: number) => {
    const p: any = {
      id: key,
      tabOpened: 'n',
    };

    historyService.updateSavedConsole(p).then(() => {
      indexedDB.deleteValue(String(key));
    });
  };

  const rememberClosedWorkspaceTabs = (tabs: IWorkspaceTab[]) => {
    if (!tabs.length) {
      return;
    }
    const currentEditorList = useWorkspaceStore.getState().editorList || {};
    const snapshots = tabs
      .map((tab) => createPersistableWorkspaceTabSnapshot(tab, currentEditorList[tab.id]?.getValue?.()))
      .filter(Boolean) as IWorkspaceTab[];
    if (!snapshots.length) {
      return;
    }
    const currentRecentlyClosed = useWorkspaceStore.getState().recentlyClosedWorkspaceTabs || [];
    const nextRecentlyClosedWorkspaceTabs = [...snapshots, ...currentRecentlyClosed].slice(
      0,
      RECENTLY_CLOSED_WORKSPACE_TAB_LIMIT,
    );
    useWorkspaceStore.setState({
      recentlyClosedWorkspaceTabs: nextRecentlyClosedWorkspaceTabs,
    });
  };

  const closeWorkspaceTabs = (tabs: IWorkspaceTab[]) => {
    const closableTabs = tabs.filter((item) => !item.pinned);
    if (!closableTabs.length) {
      return;
    }
    const closeTabIds = new Set(closableTabs.map((item) => item.id));
    const nextWorkspaceTabList = (workspaceTabList || []).filter((item) => !closeTabIds.has(item.id));
    const orderedNextWorkspaceTabList = orderPinnedWorkspaceTabsFirst(nextWorkspaceTabList);
    const nextActiveConsoleId = getNextActiveWorkspaceTabIdAfterClose({
      activeConsoleId,
      closeTabIds,
      layout: workspaceTabSplitLayout,
      orderedNextWorkspaceTabList,
    });
    rememberClosedWorkspaceTabs(closableTabs);
    setWorkspaceTabsState(orderedNextWorkspaceTabList, workspaceTabSplitLayout, nextActiveConsoleId);

    if (closeTabIds.has(activeConsoleId as any)) {
      setActiveConsoleId(nextActiveConsoleId);
    }

    closableTabs.forEach((item) => {
      if (editorList && editorList[item.id]) {
        useWorkspaceStore.getState().deleteEditor(item.id);
      }
      const closeId = item.uniqueData?.consoleId ?? item.id;
      if (isSavedConsoleLikeWorkspaceTab(item) && typeof closeId === 'number' && !isTemporaryId(closeId)) {
        closeWindowTab(closeId);
      }
    });
  };

  const createNewConsole = (targetPaneId?: WorkspaceTabPaneId) => {
    const appendNewConsoleToActivePane = (consoleId: string | number) => {
      const currentLayout = useWorkspaceStore.getState().workspaceTabSplitLayout;
      if (!currentLayout) {
        return;
      }
      const activePaneId = targetPaneId || currentLayout.activePane || MAIN_WORKSPACE_TAB_PANE;
      updateWorkspaceTabSplitLayout({
        ...currentLayout,
        activePane: activePaneId,
        paneTabIds: {
          ...currentLayout.paneTabIds,
          [activePaneId]: [...(currentLayout.paneTabIds[activePaneId] || []), consoleId],
        },
        activeTabIds: {
          ...currentLayout.activeTabIds,
          [activePaneId]: consoleId,
        },
      });
    };

    if (zoerBoundInfo) {
      const param: any = zoerBoundInfo;
      createConsole(param).then(appendNewConsoleToActivePane);
      return;
    }
    if (currentTreeNode?.extraParams?.dataSourceId) {
      const param = {
        dataSourceId: currentTreeNode.extraParams.dataSourceId,
        dataSourceName: currentTreeNode.extraParams.dataSourceName!,
        databaseType: currentTreeNode.extraParams.databaseType!,
        databaseName: currentTreeNode.extraParams.databaseName,
        schemaName: currentTreeNode.extraParams.schemaName,
      };
      createConsole(param).then(appendNewConsoleToActivePane);
    } else if (dataSourceList?.[0]?.extraParams) {
      const param: any = {
        dataSourceId: dataSourceList[0].extraParams.dataSourceId,
        dataSourceName: dataSourceList[0].extraParams.dataSourceName,
        databaseType: dataSourceList[0].extraParams.databaseType,
      };
      createConsole(param).then(appendNewConsoleToActivePane);
    }
  };

  // Delete or add a tab.
  const handelTabsEdit = (
    action: 'add' | 'remove',
    data: ITabItem[],
    paneId: WorkspaceTabPaneId = MAIN_WORKSPACE_TAB_PANE,
  ) => {
    if (action === 'remove') {
      const closeKeySet = new Set((data || []).map((item) => item.key));
      const closeTabs = (workspaceTabList || []).filter((item) => closeKeySet.has(item.id));
      closeWorkspaceTabs(closeTabs);
    }
    if (action === 'add') {
      createNewConsole(paneId);
    }
  };

  // Switch tabs.
  const onTabChange = (key: string | number | null) => {
    setActiveConsoleId(key);
  };

  const onPaneTabChange = (paneId: WorkspaceTabPaneId, key: string | number | null) => {
    if (!key) {
      return;
    }
    const nextLayout = workspaceTabSplitLayout
      ? {
          ...workspaceTabSplitLayout,
          activePane: paneId,
          activeTabIds: {
            ...workspaceTabSplitLayout.activeTabIds,
            [paneId]: key,
          },
        }
      : null;
    updateWorkspaceTabSplitLayout(nextLayout);
    setActiveConsoleId(key);
  };

  const getWorkspaceTabByKey = (key: string | number) => {
    return (workspaceTabList || []).find((item) => item.id === key);
  };

  const getPaneIdByTabKey = (key: string | number) => {
    return getPaneIdForTab(workspaceTabSplitLayout, key);
  };

  const getPaneWorkspaceTabs = (paneId: WorkspaceTabPaneId) => {
    return getWorkspaceTabListByPane(workspaceTabList || [], workspaceTabSplitLayout || null, paneId);
  };

  // Edit the name.
  const editableNameOnBlur = (t: ITabItem) => {
    const workspaceTab = getWorkspaceTabByKey(t.key);
    const savedConsoleId = workspaceTab?.uniqueData?.consoleId ?? workspaceTab?.id ?? t.key;
    if (
      workspaceTab &&
      isSavedConsoleLikeWorkspaceTab(workspaceTab) &&
      typeof savedConsoleId === 'number' &&
      !isTemporaryId(savedConsoleId)
    ) {
      const _params: any = {
        id: savedConsoleId,
        name: t.label,
      };
      historyService.updateSavedConsole(_params);
    }

    const _workspaceTabList: any =
      workspaceTabList?.map((item) => {
        if (item.id === t.key) {
          return {
            ...item,
            title: t.label,
          };
        }
        return item;
      }) || [];
    setWorkspaceTabsState(_workspaceTabList, workspaceTabSplitLayout);
  };

  // Update tab details.
  const changeTabDetails = (data: IWorkspaceTab) => {
    const list =
      workspaceTabList?.map((t) => {
        if (t.id === data.id) {
          return data;
        }
        return t;
      }) || [];
    setWorkspaceTabsState(list, workspaceTabSplitLayout);
  };

  const togglePinWorkspaceTab = (tab: ITabItem) => {
    const nextWorkspaceTabList = (workspaceTabList || []).map((item) =>
      item.id === tab.key
        ? {
            ...item,
            pinned: !item.pinned,
          }
        : item,
    );
    setWorkspaceTabsState(nextWorkspaceTabList, workspaceTabSplitLayout);
  };

  const closeWorkspaceTabsToLeft = (tab: ITabItem) => {
    const currentList = getPaneWorkspaceTabs(getPaneIdByTabKey(tab.key));
    const currentIndex = currentList.findIndex((item) => item.id === tab.key);
    if (currentIndex === -1) {
      return;
    }
    const closeTabs = currentList.slice(0, currentIndex).filter((item) => !item.pinned);
    closeWorkspaceTabs(closeTabs);
  };

  const closeWorkspaceTabsToRight = (tab: ITabItem) => {
    const currentList = getPaneWorkspaceTabs(getPaneIdByTabKey(tab.key));
    const currentIndex = currentList.findIndex((item) => item.id === tab.key);
    if (currentIndex === -1) {
      return;
    }
    const closeTabs = currentList.slice(currentIndex + 1).filter((item) => !item.pinned);
    closeWorkspaceTabs(closeTabs);
  };

  const copyWorkspaceTabReference = (tab: ITabItem) => {
    const workspaceTab = getWorkspaceTabByKey(tab.key);
    if (!workspaceTab) {
      return;
    }
    copyToClipboard(getWorkspaceTabReference(workspaceTab));
    staticMessage.success(i18n('common.button.copySuccessfully'));
  };

  const duplicateWorkspaceTab = (tab: ITabItem) => {
    const workspaceTab = getWorkspaceTabByKey(tab.key);
    if (!workspaceTab) {
      return;
    }
    const ddl = useWorkspaceStore.getState().editorList?.[workspaceTab.id]?.getValue?.();
    const duplicateTab = createTemporaryWorkspaceTabCopy(workspaceTab, ddl, 'duplicate', `${workspaceTab.title} copy`);
    const nextWorkspaceTabList = [...(workspaceTabList || []), duplicateTab];
    const sourcePaneId = getPaneIdByTabKey(tab.key);
    const nextLayout = workspaceTabSplitLayout
      ? {
          ...workspaceTabSplitLayout,
          activePane: sourcePaneId,
          paneTabIds: {
            ...workspaceTabSplitLayout.paneTabIds,
            [sourcePaneId]: [...(workspaceTabSplitLayout.paneTabIds[sourcePaneId] || []), duplicateTab.id],
          },
          activeTabIds: {
            ...workspaceTabSplitLayout.activeTabIds,
            [sourcePaneId]: duplicateTab.id,
          },
        }
      : workspaceTabSplitLayout;
    setWorkspaceTabsState(nextWorkspaceTabList, nextLayout, duplicateTab.id);
    setActiveConsoleId(duplicateTab.id);
  };

  const reopenClosedWorkspaceTab = () => {
    const [closedTab, ...restClosedTabs] = recentlyClosedWorkspaceTabs || [];
    if (!closedTab) {
      return;
    }
    const savedConsoleId = closedTab.uniqueData?.consoleId ?? closedTab.id;
    const canReopenSavedConsole =
      isSavedConsoleLikeWorkspaceTab(closedTab) && typeof savedConsoleId === 'number' && !isTemporaryId(savedConsoleId);
    const nextTab = canReopenSavedConsole
      ? {
          ...closedTab,
          pinned: false,
          uniqueData: {
            ...closedTab.uniqueData,
            consoleId: savedConsoleId,
          },
        }
      : createTemporaryWorkspaceTabCopy(closedTab, closedTab.uniqueData?.ddl, 'reopen', closedTab.title);
    useWorkspaceStore.setState({ recentlyClosedWorkspaceTabs: restClosedTabs });
    if ((workspaceTabList || []).some((item) => item.id === nextTab.id)) {
      setActiveConsoleId(nextTab.id);
      return;
    }
    if (canReopenSavedConsole) {
      historyService.updateSavedConsole({
        id: savedConsoleId,
        tabOpened: ConsoleOpenedStatus.IS_OPEN,
      });
    }
    const activePaneId = workspaceTabSplitLayout?.activePane || MAIN_WORKSPACE_TAB_PANE;
    const nextLayout = workspaceTabSplitLayout
      ? {
          ...workspaceTabSplitLayout,
          activePane: activePaneId,
          paneTabIds: {
            ...workspaceTabSplitLayout.paneTabIds,
            [activePaneId]: [...(workspaceTabSplitLayout.paneTabIds[activePaneId] || []), nextTab.id],
          },
          activeTabIds: {
            ...workspaceTabSplitLayout.activeTabIds,
            [activePaneId]: nextTab.id,
          },
        }
      : workspaceTabSplitLayout;
    setWorkspaceTabsState([...(workspaceTabList || []), nextTab], nextLayout, nextTab.id);
    setActiveConsoleId(nextTab.id);
  };

  const reorderWorkspaceTabs = (tabs: ITabItem[], sourceTab?: ITabItem) => {
    if (!workspaceTabSplitLayout || !sourceTab) {
      const workspaceTabMap = new Map((workspaceTabList || []).map((item) => [item.id, item]));
      const nextWorkspaceTabList = tabs.map((tab) => workspaceTabMap.get(tab.key)).filter(Boolean) as IWorkspaceTab[];
      setWorkspaceTabsState(nextWorkspaceTabList, workspaceTabSplitLayout);
      return;
    }

    const paneId = getPaneIdByTabKey(sourceTab.key);
    const nextPaneIds = tabs.map((tab) => tab.key);
    const nextLayout = {
      ...workspaceTabSplitLayout,
      paneTabIds: {
        ...workspaceTabSplitLayout.paneTabIds,
        [paneId]: nextPaneIds,
      },
    };
    const workspaceTabMap = getWorkspaceTabMap(workspaceTabList || []);
    const nextWorkspaceTabIds = getWorkspaceTabIdsByLayout(nextLayout);
    const nextWorkspaceTabList = nextWorkspaceTabIds
      .map((id) => workspaceTabMap.get(id))
      .filter(Boolean) as IWorkspaceTab[];
    setWorkspaceTabsState(nextWorkspaceTabList, nextLayout);
  };

  const moveWorkspaceTabInSplitLayout = (
    sourceTabId: string | number,
    targetPaneId: WorkspaceTabPaneId,
    overTabId?: string | number,
  ) => {
    const currentLayout = workspaceTabSplitLayout;
    if (!currentLayout) {
      return;
    }

    const sourcePaneId = getPaneIdForTab(currentLayout, sourceTabId);
    const sourcePaneIds = currentLayout.paneTabIds[sourcePaneId] || [];
    const sourceIndex = sourcePaneIds.findIndex((id) => id === sourceTabId);
    if (sourceIndex === -1) {
      return;
    }

    if (sourcePaneId === targetPaneId) {
      const nextPaneIds = sourcePaneIds.filter((id) => id !== sourceTabId);
      const targetIndex =
        overTabId !== undefined && overTabId !== sourceTabId ? nextPaneIds.findIndex((id) => id === overTabId) : -1;
      nextPaneIds.splice(targetIndex >= 0 ? targetIndex : nextPaneIds.length, 0, sourceTabId);

      if (JSON.stringify(nextPaneIds) === JSON.stringify(sourcePaneIds)) {
        return;
      }

      const nextLayout = {
        ...currentLayout,
        activePane: sourcePaneId,
        paneTabIds: {
          ...currentLayout.paneTabIds,
          [sourcePaneId]: nextPaneIds,
        },
        activeTabIds: {
          ...currentLayout.activeTabIds,
          [sourcePaneId]: sourceTabId,
        },
      } as IWorkspaceTabSplitLayout;
      const workspaceTabMap = getWorkspaceTabMap(workspaceTabList || []);
      const nextWorkspaceTabList = getWorkspaceTabIdsByLayout(nextLayout)
        .map((id) => workspaceTabMap.get(id))
        .filter(Boolean) as IWorkspaceTab[];
      setWorkspaceTabsState(nextWorkspaceTabList, nextLayout, sourceTabId);
      setActiveConsoleId(sourceTabId);
      return;
    }

    const targetPaneIds = currentLayout.paneTabIds[targetPaneId] || [];
    const nextSourcePaneIds = sourcePaneIds.filter((id) => id !== sourceTabId);
    const nextTargetPaneIds = targetPaneIds.filter((id) => id !== sourceTabId);
    const targetIndex =
      overTabId !== undefined && overTabId !== sourceTabId
        ? nextTargetPaneIds.findIndex((id) => id === overTabId)
        : -1;
    nextTargetPaneIds.splice(targetIndex >= 0 ? targetIndex : nextTargetPaneIds.length, 0, sourceTabId);

    const nextLayout = {
      ...currentLayout,
      activePane: targetPaneId,
      paneTabIds: {
        ...currentLayout.paneTabIds,
        [sourcePaneId]: nextSourcePaneIds,
        [targetPaneId]: nextTargetPaneIds,
      },
      activeTabIds: {
        ...currentLayout.activeTabIds,
        [sourcePaneId]: getActivePaneTabId(nextSourcePaneIds, currentLayout.activeTabIds[sourcePaneId]),
        [targetPaneId]: sourceTabId,
      },
    } as IWorkspaceTabSplitLayout;
    const workspaceTabMap = getWorkspaceTabMap(workspaceTabList || []);
    const nextWorkspaceTabList = getWorkspaceTabIdsByLayout(nextLayout)
      .map((id) => workspaceTabMap.get(id))
      .filter(Boolean) as IWorkspaceTab[];
    setWorkspaceTabsState(nextWorkspaceTabList, nextLayout, sourceTabId);
    setActiveConsoleId(sourceTabId);
  };

  const handleSplitTabDragStart = (event: DragStartEvent) => {
    setDraggingWorkspaceTabKey(String(event.active.id));
  };

  const handleSplitTabDragEnd = (event: DragEndEvent) => {
    setDraggingWorkspaceTabKey(undefined);
    if (!workspaceTabSplitLayout || !event.over) {
      return;
    }

    const activeTabId = getWorkspaceTabIdFromDndId(String(event.active.id), workspaceTabList || []);
    if (activeTabId === undefined) {
      return;
    }

    const overId = String(event.over.id);
    const overPaneId = getWorkspaceTabPaneIdFromDroppableId(overId);
    const overTabId = getWorkspaceTabIdFromDndId(overId, workspaceTabList || []);
    const targetPaneId =
      overPaneId ||
      (overTabId !== undefined ? getPaneIdForTab(workspaceTabSplitLayout, overTabId) : undefined);
    if (!targetPaneId) {
      return;
    }

    moveWorkspaceTabInSplitLayout(activeTabId, targetPaneId, overTabId);
  };

  const splitWorkspaceTab = (
    tab: ITabItem,
    direction: WorkspaceTabSplitDirection,
    splitMode: 'copy' | 'move',
  ) => {
    const workspaceTab = getWorkspaceTabByKey(tab.key);
    if (!workspaceTab) {
      return;
    }
    const currentList = workspaceTabList || [];
    const sourcePaneId = getPaneIdByTabKey(tab.key);
    const currentLayout =
      workspaceTabSplitLayout ||
      ({
        direction,
        activePane: sourcePaneId,
        root: createDefaultSplitRoot(direction),
        paneTabIds: {
          main: currentList.map((item) => item.id),
          split: [],
        },
        activeTabIds: {
          main: activeConsoleId,
          split: null,
        },
      } as IWorkspaceTabSplitLayout);
    const currentRoot = currentLayout.root || createDefaultSplitRoot(currentLayout.direction || direction);
    const targetPaneId = createWorkspaceTabPaneId();

    if (splitMode === 'move' && (currentLayout.paneTabIds[sourcePaneId] || []).length <= 1) {
      staticMessage.warning(i18n('workspace.tips.cannotMoveLastSplitTab'));
      return;
    }

    const nextWorkspaceTabList = [...currentList];
    let nextTabId = workspaceTab.id;
    if (splitMode === 'copy') {
      const ddl = useWorkspaceStore.getState().editorList?.[workspaceTab.id]?.getValue?.();
      const splitTab = createTemporaryWorkspaceTabCopy(workspaceTab, ddl, `split_${direction}`, workspaceTab.title);
      nextWorkspaceTabList.push(splitTab);
      nextTabId = splitTab.id;
    }

    const originalSourcePaneIds = currentLayout.paneTabIds[sourcePaneId] || [];
    const sourcePaneIds = originalSourcePaneIds.filter((id) => id !== workspaceTab.id);
    const targetPaneIds = [nextTabId];
    const nextRoot = findWorkspaceTabPaneNode(currentRoot, sourcePaneId)
      ? replaceWorkspaceTabPaneNode(currentRoot, sourcePaneId, {
          type: 'split',
          direction,
          first: createPaneNode(sourcePaneId),
          second: createPaneNode(targetPaneId),
        })
      : currentRoot;
    const nextLayout = {
      ...currentLayout,
      direction: currentRoot.type === 'split' ? currentRoot.direction : direction,
      root: nextRoot,
      activePane: targetPaneId,
      paneTabIds: {
        ...currentLayout.paneTabIds,
        [sourcePaneId]: splitMode === 'move' ? sourcePaneIds : originalSourcePaneIds,
        [targetPaneId]: targetPaneIds,
      },
      activeTabIds: {
        ...currentLayout.activeTabIds,
        [sourcePaneId]: getActivePaneTabId(
          splitMode === 'move' ? sourcePaneIds : originalSourcePaneIds,
          currentLayout.activeTabIds[sourcePaneId],
        ),
        [targetPaneId]: nextTabId,
      },
    } as IWorkspaceTabSplitLayout;

    setWorkspaceTabsState(nextWorkspaceTabList, nextLayout, nextTabId);
    setActiveConsoleId(nextTabId);
  };

  // Render the SQL executor.
  const renderSQLExecute = (item: IWorkspaceTab) => {
    const uniqueData = rebuildSqlExecuteTabData(item);
    if (!uniqueData) {
      return;
    }

    const { ddl = '', loadSQL } = uniqueData;
    const sqlActionEnabled =
      item.type !== WorkspaceTabType.LocalSQLFile ||
      (uniqueData.fileExtension || '').toLowerCase() === SQL_FILE_EXTENSION_NAME;

    const itemConsoleId = uniqueData.consoleId ?? item.id;
    const consoleId =
      item.type === WorkspaceTabType.CONSOLE && typeof itemConsoleId === 'number' ? itemConsoleId : undefined;
    const boundInfo = {
      ...uniqueData,
      ...getDatabaseSupport(uniqueData?.databaseType),
      workspaceTabId: item.id,
      consoleId,
    };

    delete boundInfo.ddl;
    delete boundInfo.loadSQL;

    return (
      <SQLExecute
        workspaceTabsTitle={item.title}
        boundInfo={boundInfo}
        type={item.type as EditorType}
        initDDL={ddl}
        loadSQL={loadSQL}
        sqlActionEnabled={sqlActionEnabled}
      />
    );
  };

  // Render the table editor.
  const renderTableEditor = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    if (!uniqueData) {
      return;
    }
    return (
      <DatabaseTableEditor
        tabDetails={item}
        changeTabDetails={changeTabDetails}
        databaseBaseInfo={{
          dataSourceId: uniqueData.dataSourceId!,
          databaseType: uniqueData.databaseType!,
          databaseName: uniqueData.databaseName!,
          schemaName: uniqueData.schemaName,
          tableName: uniqueData.tableName,
        }}
        submitCallback={(uniqueData as any).submitCallback}
      />
    );
  };

  // Render search results.
  const renderSearchResult = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    if (!uniqueData) {
      return;
    }
    return (
      <ViewTable
        viewTableParams={{
          dataSourceId: uniqueData?.dataSourceId,
          databaseName: uniqueData?.databaseName,
          schemaName: uniqueData?.schemaName,
          databaseType: uniqueData?.databaseType,
          tableName: uniqueData?.tableName,
        }}
      />
    );
  };

  // Render all tables.
  const renderViewAllTable = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    if (!uniqueData) {
      return;
    }
    return <NewViewAllTable uniqueData={uniqueData as any} />;
  };

  // Render the ER diagram.
  const renderERModal = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    if (!uniqueData) {
      return;
    }
    return <ConsoleERModal uniqueData={uniqueData!} />;
  };

  const renderAiDataCollection = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    if (!uniqueData) {
      return;
    }
    return <ChangeAiTableInfo uniqueData={uniqueData as any} />;
  };

  const renderRedisAllData = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    if (!uniqueData) {
      return;
    }
    return <RedisAllData uniqueData={uniqueData as any} />;
  };

  const renderAccountPrivileges = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    if (!uniqueData) {
      return;
    }
    return <AccountPrivilegePanel uniqueData={uniqueData} />;
  };

  const renderContentDiff = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    return (
      <ContentDiffTab
        originalText={uniqueData?.diffOriginalText}
        modifiedText={uniqueData?.diffModifiedText}
        language={uniqueData?.diffLanguage}
      />
    );
  };

  // Render content according to the tab type.
  const workspaceTabConnectionMap = (item: IWorkspaceTab) => {
    switch (item.type) {
      case 'table' as any: // Backward compatibility with legacy data.
      case null as any: // Backward compatibility with legacy data.
      case WorkspaceTabType.CONSOLE:
      case WorkspaceTabType.LocalSQLFile:
      case WorkspaceTabType.FUNCTION:
      case WorkspaceTabType.PROCEDURE:
      case WorkspaceTabType.TRIGGER:
      case WorkspaceTabType.VIEW:
        return renderSQLExecute(item);
      case WorkspaceTabType.EditTable:
      case WorkspaceTabType.CreateTable:
        return renderTableEditor(item);
      case WorkspaceTabType.EditTableData:
      case WorkspaceTabType.ViewView:
        return renderSearchResult(item);
      case WorkspaceTabType.ViewAllTable:
      case WorkspaceTabType.ViewAllView:
        return renderViewAllTable(item);
      case WorkspaceTabType.ViewERModal:
        return renderERModal(item);
      case WorkspaceTabType.ChangeAiTableInfo:
        return renderAiDataCollection(item);
      case WorkspaceTabType.RedisAllData:
        return renderRedisAllData(item);
      case WorkspaceTabType.AccountPrivileges:
        return renderAccountPrivileges(item);
      case WorkspaceTabType.ContentDiff:
        return renderContentDiff(item);
      default:
        return <div>Unknown</div>;
    }
  };

  const getWorkspaceTabItems = (tabs: IWorkspaceTab[]) => {
    return tabs.map((item) => {
      const popoverContent = item.uniqueData?.popoverContent;
      return {
        prefixIcon:
          item.type === WorkspaceTabType.LocalSQLFile
            ? getLocalTextFileIcon(item.uniqueData?.fileExtension)
            : workspaceTabConfig[item.type]?.icon,
        label: item.title,
        popover: popoverContent ? <div style={{ padding: '4px 6px' }}>{popoverContent}</div> : undefined,
        key: item.id,
        editableName: item.type === WorkspaceTabType.CONSOLE,
        pinned: item.pinned,
        children: <Fragment key={item.id}>{workspaceTabConnectionMap(item)}</Fragment>,
      };
    });
  };

  // Tab list.
  const workspaceTabItems = useMemo(() => {
    return getWorkspaceTabItems(workspaceTabList || []);
  }, [workspaceTabList, activeConsoleId]);

  function renderCreateConsoleButton() {
    if (!canCreateConsole) {
      return null;
    }
    return (
      <div className={styles.createButtonBox}>
        <Button
          className={styles.createButton}
          type="primary"
          onClick={() => {
            createNewConsole();
          }}
        >
          <Iconfont code="&#xe63a;" />
          {i18n('common.button.createConsole')}
        </Button>
      </div>
    );
  }

  const showWorkspaceRightEmpty = !zoerBoundInfo;
  const hideAdd = !canCreateConsole || !!zoerBoundInfo;
  const commonWorkspaceTabContextActions = {
    closeLeft: true,
    closeRight: true,
    pin: true,
    duplicate: true,
    copyReference: true,
    splitRight: true,
    splitAndMoveRight: true,
    splitDown: true,
    splitAndMoveDown: true,
    reopenClosed: !!recentlyClosedWorkspaceTabs?.length,
  };
  const commonWorkspaceTabContextActionHandlers = {
    closeLeft: closeWorkspaceTabsToLeft,
    closeRight: closeWorkspaceTabsToRight,
    pin: togglePinWorkspaceTab,
    duplicate: duplicateWorkspaceTab,
    copyReference: copyWorkspaceTabReference,
    splitRight: (tab: ITabItem) => splitWorkspaceTab(tab, 'vertical', 'copy'),
    splitAndMoveRight: (tab: ITabItem) => splitWorkspaceTab(tab, 'vertical', 'move'),
    splitDown: (tab: ITabItem) => splitWorkspaceTab(tab, 'horizontal', 'copy'),
    splitAndMoveDown: (tab: ITabItem) => splitWorkspaceTab(tab, 'horizontal', 'move'),
    reopenClosed: reopenClosedWorkspaceTab,
    reorder: reorderWorkspaceTabs,
  };

  const getWorkspaceTabContextActionAvailability = (tab: ITabItem): ITabContextActions => {
    const workspaceTab = getWorkspaceTabByKey(tab.key);
    const paneTabs = getPaneWorkspaceTabs(getPaneIdByTabKey(tab.key));
    const canCopyTab = canCopyWorkspaceTab(workspaceTab);
    const canSplitTab = !!workspaceTab;
    const canMoveSplitTab = canSplitTab && paneTabs.length > 1;
    return {
      duplicate: canCopyTab,
      splitRight: canCopyTab,
      splitDown: canCopyTab,
      splitAndMoveRight: canMoveSplitTab,
      splitAndMoveDown: canMoveSplitTab,
    };
  };

  const updateSplitPaneSize = (path: WorkspaceTabSplitNodePath, size: number | string) => {
    const currentLayout = useWorkspaceStore.getState().workspaceTabSplitLayout;
    const root = currentLayout?.root;
    if (!currentLayout || !root) {
      return;
    }
    useWorkspaceStore.setState({
      workspaceTabSplitLayout: {
        ...currentLayout,
        root: updateWorkspaceTabSplitNodeSize(root, path, size),
      },
    });
  };

  function renderWorkspaceTabPane(
    paneId: WorkspaceTabPaneId,
    className?: string,
  ) {
    const items = getWorkspaceTabItems(getPaneWorkspaceTabs(paneId));
    const activeKey = workspaceTabSplitLayout?.activeTabIds[paneId];
    return (
      <div
        className={className}
        onMouseDownCapture={() => {
          if (activeKey && activeKey !== activeConsoleId) {
            onPaneTabChange(paneId, activeKey);
          }
        }}
      >
        <CustomTabs
          height={36}
          hideAdd={hideAdd}
          className={styles.tabBox}
          onChange={(key) => onPaneTabChange(paneId, key)}
          onEdit={(action, data) => handelTabsEdit(action, data || [], paneId)}
          activeKey={activeKey}
          editableNameOnBlur={editableNameOnBlur}
          items={items}
          contextActions={commonWorkspaceTabContextActions}
          contextActionAvailability={getWorkspaceTabContextActionAvailability}
          contextActionHandlers={commonWorkspaceTabContextActionHandlers}
          useExternalSortableContext={!!workspaceTabSplitLayout}
          draggingTabKey={draggingWorkspaceTabKey}
          onDraggingTabKeyChange={setDraggingWorkspaceTabKey}
          tabPaneDroppableId={workspaceTabSplitLayout ? getWorkspaceTabPaneDroppableId(paneId) : undefined}
        />
      </div>
    );
  }

  function renderWorkspaceTabPaneNode(
    node: IWorkspaceTabPaneNode,
    path: WorkspaceTabSplitNodePath = [],
  ): React.ReactNode {
    if (node.type === 'pane') {
      return renderWorkspaceTabPane(node.id, styles.splitPaneItem);
    }

    return (
      <SplitPaneAny
        key={`${node.direction}:${collectWorkspaceTabPaneIds(node).join('|')}`}
        className={styles.splitPane}
        split={node.direction}
        primary="first"
        size={node.size ?? '50%'}
        minSize={180}
        paneClassName={styles.splitPaneInner}
        onDragFinished={(size: number | string) => updateSplitPaneSize(path, size)}
      >
        {renderWorkspaceTabPaneNode(node.first, [...path, 'first'])}
        {renderWorkspaceTabPaneNode(node.second, [...path, 'second'])}
      </SplitPaneAny>
    );
  }

  return workspaceTabItems?.length ? (
    workspaceTabSplitLayout ? (
      <DndContext
        sensors={splitTabDragSensors}
        collisionDetection={workspaceTabCollisionDetection}
        onDragStart={handleSplitTabDragStart}
        onDragEnd={handleSplitTabDragEnd}
        onDragCancel={() => setDraggingWorkspaceTabKey(undefined)}
      >
        <div className={styles.splitTabBox}>
          {renderWorkspaceTabPaneNode(
            workspaceTabSplitLayout.root || createDefaultSplitRoot(workspaceTabSplitLayout.direction),
          )}
        </div>
      </DndContext>
    ) : (
      <CustomTabs
        height={36}
        hideAdd={hideAdd}
        className={styles.tabBox}
        onChange={onTabChange as any}
        onEdit={(action, data) => handelTabsEdit(action, data || [], MAIN_WORKSPACE_TAB_PANE)}
        activeKey={activeConsoleId}
        editableNameOnBlur={editableNameOnBlur}
        items={workspaceTabItems}
        contextActions={commonWorkspaceTabContextActions}
        contextActionAvailability={getWorkspaceTabContextActionAvailability}
        contextActionHandlers={commonWorkspaceTabContextActionHandlers}
      />
    )
  ) : (
    <>
      {showWorkspaceRightEmpty && (
        <div className={styles.ears}>
          <WorkspaceRightEmpty slot={renderCreateConsoleButton} />
        </div>
      )}
    </>
  );
});

export default WorkspaceTabs;
