import NewTree from '@/blocks/NewTree';
import CreateDatabase from '@/components/CreateDatabase';
import { SAVED_CONSOLE_UPDATED_EVENT, TreeNodeType, type SavedConsoleUpdatedEventDetail } from '@/constants';
import i18n from '@/i18n';
import MainSecondaryPanel from '@/pages/main/components/MainSecondaryPanel';
import { getTreeStoreLifecycleVersion, useTreeStore } from '@/store/tree';
import { useWorkspaceStore } from '@/store/workspace';
import type { TreeNodeData } from '@/typings';
import { isCommunityEnv, isDesktop, isDesktopEnv, isOfflineEnv, isWebEnv } from '@/utils/env';
import feedback from '@/utils/feedback';
import { COMMUNITY_TITLE_BAR_BUTTON_SIZE } from '@/constants/mainLayout';
import { IconButton } from '@chat2db/ui';
import { Dropdown, Flex, type MenuProps } from 'antd';
import { ChevronDown, Database, Folder, PanelLeft, type LucideIcon } from 'lucide-react';
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
import { shouldProbeDesktopBridge } from './desktopBridge';
import { loadDatabaseTreePath } from './loadDatabaseTreePath';
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
  const canProbeDesktopBridge = shouldProbeDesktopBridge({
    isWebEnv,
    isDesktopEnv,
    isOfflineEnv,
    isCommunityEnv,
    isDesktop,
  });
  const [desktopBridgeReady, setDesktopBridgeReady] = useState(() => isDesktop || hasDesktopBridge());
  const { styles } = useStyles();
  const showExplorerPanel = canProbeDesktopBridge && desktopBridgeReady;
  const { activeConsoleId, togglePanelLeft, workspaceTabList } = useWorkspaceStore((state) => ({
    activeConsoleId: state.activeConsoleId,
    togglePanelLeft: state.togglePanelLeft,
    workspaceTabList: state.workspaceTabList,
  }));
  const { changeUserConfigTree, treeDataReady, treeDataRevision, userConfigTree } = useTreeStore((state) => ({
    changeUserConfigTree: state.changeUserConfigTree,
    treeDataReady: !!state.treeData,
    treeDataRevision: state.treeDataRevision,
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
  const panelOptions: Array<{ icon: LucideIcon; label: string; value: WorkspaceLeftPanel }> = [
    { icon: Database, label: i18n('workspace.explorer.dataSources'), value: 'database' },
    { icon: Folder, label: i18n('workspace.explorer.title'), value: 'explorer' },
  ];
  const visiblePanelOptions = showExplorerPanel
    ? panelOptions
    : panelOptions.filter((item) => item.value === 'database');
  const currentPanelOption = panelOptions.find((item) => item.value === currentPanel) || panelOptions[0];
  const CurrentPanelIcon = currentPanelOption.icon;
  const panelMenuItems: MenuProps['items'] = visiblePanelOptions.map((item) => {
    const OptionIcon = item.icon;
    return {
      key: item.value,
      label: (
        <span className={styles.resourceMenuItem}>
          <OptionIcon aria-hidden size={15} strokeWidth={1.8} />
          <span>{item.label}</span>
        </span>
      ),
    };
  });
  const showResourceSwitcher = showExplorerPanel || isCommunityEnv;
  const locateDisabled = !activeTabLocateTarget;

  useEffect(() => {
    if (!canProbeDesktopBridge || desktopBridgeReady) {
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
  }, [canProbeDesktopBridge, desktopBridgeReady]);

  useEffect(
    () => () => {
      locateRequestSeqRef.current += 1;
    },
    [],
  );

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

  const locateDatabaseTree = useCallback(
    async (
      target: DatabaseLocateTarget,
      options?: { clearSearch?: boolean; requestSeq?: number },
    ): Promise<LocateStatus> => {
      if (options?.requestSeq !== undefined && options.requestSeq !== locateRequestSeqRef.current) {
        return 'miss';
      }

      const lifecycleVersion = getTreeStoreLifecycleVersion();
      const isCurrent = () =>
        (options?.requestSeq === undefined || options.requestSeq === locateRequestSeqRef.current) &&
        lifecycleVersion === getTreeStoreLifecycleVersion();
      const loaded = await loadDatabaseTreePath(target.loadPath, useTreeStore.getState, isCurrent);
      if (!isCurrent()) {
        return 'miss';
      }
      if (!loaded) {
        return 'miss';
      }

      const result = findDatabaseLocateNode(useTreeStore.getState().treeData, target.candidates);
      if (!isCurrent()) {
        return 'miss';
      }
      if (!result) {
        return 'miss';
      }

      selectDatabaseTreeNode(result, { clearSearch: options?.clearSearch });
      return result.fallback ? 'fallback' : 'hit';
    },
    [selectDatabaseTreeNode],
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
  }, [
    activeTabLocateTarget,
    autoFollowActiveWorkspaceTab,
    currentPanel,
    locateActiveWorkspaceTab,
    treeDataReady,
    treeDataRevision,
  ]);

  return (
    <>
      <MainSecondaryPanel tabIndex={-1} id="tree-search-area">
        {showResourceSwitcher && (
          <div className={styles.resourceSwitcher}>
            <Dropdown
              menu={{
                items: panelMenuItems,
                selectable: true,
                selectedKeys: [currentPanel],
                onClick: ({ key }) => handlePanelSelection(key as WorkspaceLeftPanel),
              }}
              placement="bottomLeft"
              trigger={['click']}
            >
              <button type="button" className={styles.resourceSelector} aria-label={currentPanelOption.label}>
                <CurrentPanelIcon aria-hidden size={16} strokeWidth={1.8} />
                <span className={styles.resourceSelectorLabel}>{currentPanelOption.label}</span>
                <ChevronDown aria-hidden size={14} strokeWidth={1.8} />
              </button>
            </Dropdown>
            {isCommunityEnv && (
              <IconButton
                size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
                title={i18n('stream.sidebar.collapse')}
                tooltipPlacement="bottom"
                icon={PanelLeft}
                onClick={() => togglePanelLeft()}
              />
            )}
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
