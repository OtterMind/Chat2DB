import NewTree from '@/blocks/NewTree';
import CreateDatabase from '@/components/CreateDatabase';
import { SAVED_CONSOLE_UPDATED_EVENT, TreeNodeType, type SavedConsoleUpdatedEventDetail } from '@/constants';
import i18n from '@/i18n';
import MainSecondaryPanel from '@/pages/main/components/MainSecondaryPanel';
import { useTreeStore } from '@/store/tree';
import { useWorkspaceStore } from '@/store/workspace';
import type { TreeNodeData } from '@/typings';
import { isCommunityEnv, isDesktop, isDesktopEnv, isWebEnv } from '@/utils/env';
import feedback from '@/utils/feedback';
import { Flex } from 'antd';
import { memo, useCallback, useEffect, useMemo, useRef, useState, type Key } from 'react';
import {
  getActiveTabLocateTargetForPanel,
  getActiveTabLocateTargets,
  resolveWorkspaceLeftPanel,
  type ActiveTabDatabaseCandidate,
  type ActiveTabLocateTarget,
  type WorkspaceLeftPanel,
} from '../../utils/activeTabLocator';
import WorkspaceExplorer, { type WorkspaceExplorerRef } from '../WorkspaceExplorer';
import WorkspaceLeftActionBar from '../WorkspaceLeftActionBar';
import { useStyles } from './style';

type DatabaseLocateTarget = Extract<ActiveTabLocateTarget, { surface: 'databaseTree' }>;
type LocateStatus = 'hit' | 'fallback' | 'miss';

interface LocatedTreeNode {
  node: TreeNodeData;
  ancestors: Key[];
  fallback?: boolean;
}

function hasDesktopBridge() {
  return typeof window.javaQuery === 'function';
}

function normalizeLocateValue(value?: string | number | null) {
  if (value === undefined || value === null || value === '') {
    return undefined;
  }
  return String(value).toLowerCase();
}

function isSameLocateValue(left?: string | number | null, right?: string | number | null) {
  return normalizeLocateValue(left) === normalizeLocateValue(right);
}

function getNodeObjectName(node: TreeNodeData) {
  if (node.treeNodeType === TreeNodeType.VIEW) {
    return node.extraParams?.viewName || node.extraParams?.tableName || node.originalTitle;
  }
  if (node.treeNodeType === TreeNodeType.FUNCTION) {
    return node.extraParams?.functionName || node.originalTitle;
  }
  if (node.treeNodeType === TreeNodeType.PROCEDURE) {
    return node.extraParams?.procedureName || node.originalTitle;
  }
  if (node.treeNodeType === TreeNodeType.TRIGGER) {
    return node.extraParams?.triggerName || node.originalTitle;
  }
  if (node.treeNodeType === TreeNodeType.DATABASE_ACCOUNT) {
    return node.extraParams?.user || node.originalTitle;
  }
  return node.extraParams?.tableName || node.originalTitle;
}

function isDatabaseCandidateMatch(node: TreeNodeData, candidate: ActiveTabDatabaseCandidate) {
  if (candidate.key && node.key === candidate.key) {
    return true;
  }
  if (!candidate.treeNodeType || node.treeNodeType !== candidate.treeNodeType) {
    return false;
  }
  if (candidate.dataSourceId !== undefined && node.extraParams?.dataSourceId !== candidate.dataSourceId) {
    return false;
  }
  if (
    normalizeLocateValue(candidate.databaseName) &&
    !isSameLocateValue(node.extraParams?.databaseName, candidate.databaseName)
  ) {
    return false;
  }
  if (
    normalizeLocateValue(candidate.schemaName) &&
    !isSameLocateValue(node.extraParams?.schemaName, candidate.schemaName)
  ) {
    return false;
  }
  if (normalizeLocateValue(candidate.name) && !isSameLocateValue(getNodeObjectName(node), candidate.name)) {
    return false;
  }
  return !!candidate.name || candidate.dataSourceId !== undefined;
}

function findTreeNodeWithAncestors(
  treeData: TreeNodeData[] | null | undefined,
  predicate: (node: TreeNodeData) => boolean,
  ancestors: Key[] = [],
): LocatedTreeNode | null {
  if (!treeData?.length) {
    return null;
  }

  for (const node of treeData) {
    if (predicate(node)) {
      return { node, ancestors };
    }

    const childNode = findTreeNodeWithAncestors(node.children, predicate, [...ancestors, node.key]);
    if (childNode) {
      return childNode;
    }
  }

  return null;
}

function findDatabaseLocateNode(treeData: TreeNodeData[] | null | undefined, candidates: ActiveTabDatabaseCandidate[]) {
  for (const candidate of candidates) {
    const result = findTreeNodeWithAncestors(treeData, (node) => isDatabaseCandidateMatch(node, candidate));
    if (result) {
      return { ...result, fallback: candidate.fallback };
    }
  }

  return null;
}

const WorkspaceLeft = memo(() => {
  const explorerRef = useRef<WorkspaceExplorerRef>(null);
  const locateRequestSeqRef = useRef(0);
  const pendingManualPanelLocateRef = useRef<WorkspaceLeftPanel | null>(null);
  const shouldProbeDesktopBridge = !isWebEnv && (isDesktopEnv || isCommunityEnv || isDesktop);
  const [desktopBridgeReady, setDesktopBridgeReady] = useState(() => isDesktop || hasDesktopBridge());
  const { styles } = useStyles();
  const showExplorerPanel = shouldProbeDesktopBridge && desktopBridgeReady;
  const { activeConsoleId, workspaceTabList } = useWorkspaceStore((state) => ({
    activeConsoleId: state.activeConsoleId,
    workspaceTabList: state.workspaceTabList,
  }));
  const { changeUserConfigTree, treeDataReady, userConfigTree } = useTreeStore((state) => ({
    changeUserConfigTree: state.changeUserConfigTree,
    treeDataReady: !!state.treeData,
    userConfigTree: state.userConfigTree,
  }));
  const activePanel = resolveWorkspaceLeftPanel(userConfigTree.workspaceLeftPanel);
  const currentPanel = showExplorerPanel ? activePanel : 'database';
  const setActivePanel = useCallback(
    (panel: WorkspaceLeftPanel) => {
      const persistedPanel = resolveWorkspaceLeftPanel(useTreeStore.getState().userConfigTree.workspaceLeftPanel);
      if (persistedPanel !== panel) {
        changeUserConfigTree('workspaceLeftPanel', panel);
      }
    },
    [changeUserConfigTree],
  );
  const activeTab = useMemo(
    () => workspaceTabList?.find((tab) => tab.id === activeConsoleId),
    [activeConsoleId, workspaceTabList],
  );
  const activeTabLocateTargets = useMemo(() => getActiveTabLocateTargets(activeTab), [activeTab]);
  const activeTabLocateTarget = getActiveTabLocateTargetForPanel(activeTabLocateTargets, currentPanel);
  const autoFollowActiveWorkspaceTab = userConfigTree.followActiveWorkspaceTab !== false;
  const panelOptions: Array<{ label: string; value: WorkspaceLeftPanel }> = [
    { label: i18n('workspace.explorer.title'), value: 'explorer' },
    { label: i18n('workspace.explorer.databases'), value: 'database' },
  ];
  const locateDisabled = !activeTabLocateTarget;

  useEffect(() => {
    if (!shouldProbeDesktopBridge || desktopBridgeReady) {
      return;
    }

    let frameId: number | undefined;
    const expiresAt = Date.now() + 3000;
    const detectBridge = () => {
      if (hasDesktopBridge()) {
        setDesktopBridgeReady(true);
        return;
      }
      if (Date.now() < expiresAt) {
        frameId = window.requestAnimationFrame(detectBridge);
      }
    };

    frameId = window.requestAnimationFrame(detectBridge);
    return () => {
      if (frameId !== undefined) {
        window.cancelAnimationFrame(frameId);
      }
    };
  }, [desktopBridgeReady, shouldProbeDesktopBridge]);

  useEffect(() => {
    const handleSavedConsoleUpdated = (event: Event) => {
      const detail = (event as CustomEvent<SavedConsoleUpdatedEventDetail>).detail;
      if (!detail) {
        return;
      }
      void useTreeStore.getState().refreshTreeNodeDataInBackground({
        ...detail,
        treeNodeType: TreeNodeType.SAVE_CONSOLES,
      });
    };

    window.addEventListener(SAVED_CONSOLE_UPDATED_EVENT, handleSavedConsoleUpdated);
    return () => {
      window.removeEventListener(SAVED_CONSOLE_UPDATED_EVENT, handleSavedConsoleUpdated);
    };
  }, []);

  const selectDatabaseTreeNode = useCallback(
    (locatedTreeNode: LocatedTreeNode, options?: { clearSearch?: boolean }) => {
      const treeStore = useTreeStore.getState();
      const { node, ancestors } = locatedTreeNode;
      if (options?.clearSearch) {
        treeStore.setSearchBarValue('');
        treeStore.setSearchResult(null);
      }
      treeStore.setExpandedKeys([...treeStore.expandedKeys, ...ancestors]);
      treeStore.setCurrentTreeNode(node);
      treeStore.setSelectedKeys([node.key]);
      treeStore.setScrollTargetKey(node.key);
    },
    [],
  );

  const loadDatabasePath = useCallback(async (loadPath: string[]) => {
    for (const key of loadPath) {
      const treeStore = useTreeStore.getState();
      const result = findTreeNodeWithAncestors(treeStore.treeData, (node) => node.key === key);
      if (!result) {
        return false;
      }

      if (result.node.children === undefined && !result.node.isLeaf) {
        try {
          await treeStore.handleLoadData(result.node);
        } catch {
          return false;
        }
      } else {
        treeStore.setExpandedKeys([...treeStore.expandedKeys, key]);
      }
    }
    return true;
  }, []);

  const locateDatabaseTree = useCallback(
    async (
      target: DatabaseLocateTarget,
      options?: { clearSearch?: boolean; requestSeq?: number },
    ): Promise<LocateStatus> => {
      if (options?.requestSeq !== undefined && options.requestSeq !== locateRequestSeqRef.current) {
        return 'miss';
      }

      const treeStore = useTreeStore.getState();
      const previousSelection = {
        currentTreeNode: treeStore.currentTreeNode,
        selectedKeys: treeStore.selectedKeys,
      };
      const loaded = await loadDatabasePath(target.loadPath);
      if (options?.requestSeq !== undefined && options.requestSeq !== locateRequestSeqRef.current) {
        treeStore.setCurrentTreeNode(previousSelection.currentTreeNode);
        treeStore.setSelectedKeys(previousSelection.selectedKeys);
        return 'miss';
      }
      if (!loaded) {
        treeStore.setCurrentTreeNode(previousSelection.currentTreeNode);
        treeStore.setSelectedKeys(previousSelection.selectedKeys);
        return 'miss';
      }

      const result = findDatabaseLocateNode(useTreeStore.getState().treeData, target.candidates);
      if (options?.requestSeq !== undefined && options.requestSeq !== locateRequestSeqRef.current) {
        return 'miss';
      }
      if (!result) {
        treeStore.setCurrentTreeNode(previousSelection.currentTreeNode);
        treeStore.setSelectedKeys(previousSelection.selectedKeys);
        return 'miss';
      }

      selectDatabaseTreeNode(result, { clearSearch: options?.clearSearch });
      return result.fallback ? 'fallback' : 'hit';
    },
    [loadDatabasePath, selectDatabaseTreeNode],
  );

  const locateActiveWorkspaceTab = useCallback(
    async (panel: WorkspaceLeftPanel = currentPanel, options?: { clearSearch?: boolean }): Promise<LocateStatus> => {
      const requestSeq = locateRequestSeqRef.current + 1;
      locateRequestSeqRef.current = requestSeq;
      const target = getActiveTabLocateTargetForPanel(activeTabLocateTargets, panel);
      if (!target) {
        return 'miss';
      }

      if (target.surface === 'explorerSession') {
        return target.sessionId === activeConsoleId ? 'hit' : 'miss';
      }

      if (target.surface === 'localFile') {
        return explorerRef.current?.locateLocalFile(target.filePath) ? 'hit' : 'miss';
      }

      return locateDatabaseTree(target, { ...options, requestSeq });
    },
    [activeConsoleId, activeTabLocateTargets, currentPanel, locateDatabaseTree],
  );

  const handleLocateActiveWorkspaceTab = useCallback(() => {
    void locateActiveWorkspaceTab(currentPanel, { clearSearch: true }).then((status) => {
      if (status === 'miss') {
        feedback.warning(i18n('workspace.tips.locateActiveTabFailed'));
      }
      if (status === 'fallback') {
        feedback.info(i18n('workspace.tips.locateActiveTabFallback'));
      }
    });
  }, [currentPanel, locateActiveWorkspaceTab]);

  const handlePanelSelection = useCallback(
    (panel: WorkspaceLeftPanel) => {
      pendingManualPanelLocateRef.current = panel;
      if (panel !== currentPanel) {
        setActivePanel(panel);
        return;
      }

      const target = getActiveTabLocateTargetForPanel(activeTabLocateTargets, panel);
      if (!target) {
        pendingManualPanelLocateRef.current = null;
        return;
      }
      if (target.surface === 'databaseTree' && !treeDataReady) {
        return;
      }

      pendingManualPanelLocateRef.current = null;
      void locateActiveWorkspaceTab(panel, { clearSearch: true });
    },
    [activeTabLocateTargets, currentPanel, locateActiveWorkspaceTab, setActivePanel, treeDataReady],
  );

  useEffect(() => {
    // Cancel an in-flight database locate even when the new target cannot be located in this panel.
    locateRequestSeqRef.current += 1;
    const isManualPanelLocate = pendingManualPanelLocateRef.current === currentPanel;
    if (!isManualPanelLocate && !autoFollowActiveWorkspaceTab) {
      return;
    }

    if (!activeTabLocateTarget) {
      if (isManualPanelLocate) {
        pendingManualPanelLocateRef.current = null;
      }
      return;
    }
    if (activeTabLocateTarget.surface === 'databaseTree' && !treeDataReady) {
      return;
    }

    if (isManualPanelLocate) {
      pendingManualPanelLocateRef.current = null;
    }
    void locateActiveWorkspaceTab(currentPanel, isManualPanelLocate ? { clearSearch: true } : undefined);
  }, [activeTabLocateTarget, autoFollowActiveWorkspaceTab, currentPanel, locateActiveWorkspaceTab, treeDataReady]);

  return (
    <>
      <MainSecondaryPanel tabIndex={-1} id="tree-search-area">
        {showExplorerPanel && (
          <div className={styles.resourceSwitcher}>
            <div className={styles.resourceTabs}>
              {panelOptions.map((item) => (
                <button
                  key={item.value}
                  type="button"
                  className={[styles.resourceTitle, activePanel === item.value ? styles.resourceTitleActive : '']
                    .filter(Boolean)
                    .join(' ')}
                  onClick={() => handlePanelSelection(item.value)}
                >
                  {item.label}
                </button>
              ))}
            </div>
          </div>
        )}
        {showExplorerPanel ? (
          <>
            <div className={[styles.panelPane, currentPanel === 'explorer' ? styles.panelPaneActive : ''].join(' ')}>
              <WorkspaceExplorer ref={explorerRef} active={currentPanel === 'explorer'} />
            </div>
            <div className={[styles.panelPane, currentPanel === 'database' ? styles.panelPaneActive : ''].join(' ')}>
              <WorkspaceLeftActionBar
                active={currentPanel === 'database'}
                onLocateActiveTab={handleLocateActiveWorkspaceTab}
                locateActiveTabDisabled={locateDisabled}
              />
              <Flex vertical style={{ flex: 1, position: 'relative', minHeight: 0 }}>
                <NewTree className={styles.treeBox} />
              </Flex>
            </div>
          </>
        ) : (
          <div className={styles.panelPaneActive}>
            <WorkspaceLeftActionBar
              onLocateActiveTab={handleLocateActiveWorkspaceTab}
              locateActiveTabDisabled={locateDisabled}
            />
            <Flex vertical style={{ flex: 1, position: 'relative', minHeight: 0 }}>
              <NewTree className={styles.treeBox} />
            </Flex>
          </div>
        )}
      </MainSecondaryPanel>
      <CreateDatabase />
    </>
  );
});

export default WorkspaceLeft;
