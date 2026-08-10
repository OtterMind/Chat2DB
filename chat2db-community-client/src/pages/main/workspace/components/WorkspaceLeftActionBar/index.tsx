import { memo, useCallback, useEffect, useMemo, useRef } from 'react';
import { useStyles } from './style';
import { IconButton } from '@chat2db/ui';
import SearchBar, { type SearchBarRef } from '@/components/SearchBar';
import { Tooltip } from 'antd';
import AddDatasourceBar from './components/AddDatasourceBar';
import TreeSetting from './components/TreeSetting';
import { useTreeStore } from '@/store/tree';
import { useOrgStore } from '@/store/organization';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';
import { searchTreeNodes } from '@/utils';
import { filterTreeNodesForDisplay } from '@/utils/filterTreeNodes';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { useUpdateEffect } from 'ahooks';
import { debounce } from 'lodash';
import {
  ShortcutAction,
  ShortcutOverrides,
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
} from '@/constants/shortcut';

interface ActionButton {
  key: string;
  icon: string;
  label: string;
  onClick: () => void;
  isHidden?: boolean;
}

interface WorkspaceLeftActionBarProps {
  active?: boolean;
  onLocateActiveTab?: () => void;
  locateActiveTabDisabled?: boolean;
}

const WorkspaceLeftActionBar = memo<WorkspaceLeftActionBarProps>(
  ({ active = true, onLocateActiveTab, locateActiveTabDisabled = false }) => {
    const searchBarRef = useRef<SearchBarRef>(null);
    const {
      refreshTreeData,
      searchBarValue,
      setSearchBarValue,
      searchResultKeys,
      hiddenTreeNodeIds,
    } = useTreeStore((s) => ({
      refreshTreeData: s.refreshTreeData,
      searchBarValue: s.searchBarValue,
      setSearchBarValue: s.setSearchBarValue,
      searchResultKeys: s.searchResultKeys,
      hiddenTreeNodeIds: s.hiddenTreeNodeIds,
    }));

    const { isEmbedIframe, shortcutOverrides } = useGlobalStore((s) => ({
      isEmbedIframe: s.isEmbedIframe,
      shortcutOverrides: s.shortcutOverrides,
    }));
    const shortcutConfig = useMemo(
      () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
      [shortcutOverrides],
    );

    const { styles } = useStyles();

    const { isAdmin } = useOrgStore((s) => {
      return {
        isAdmin: s.isAdmin,
      };
    });

    const buttonList = useMemo<ActionButton[]>(() => {
      return [
        {
          key: 'refresh',
          icon: 'icon-refresh',
          label: i18n('common.button.refresh'),
          onClick: refreshTreeData,
        },
      ];
    }, [refreshTreeData]);

    const searchBarOnChange = (e) => {
      setSearchBarValue(e.target.value);
    };

    const debouncedSearch = useCallback(
      debounce(() => {
        const treeStore = useTreeStore.getState();
        const value = treeStore.regularSearchBarValue;
        if (!value) {
          treeStore.setSearchResult(null);
          treeStore.setSearchResultKeys(null);
          return;
        }
        const visibleTreeData = filterTreeNodesForDisplay(treeStore.treeData || [], {
          hiddenTreeNodeIds: treeStore.hiddenTreeNodeIds,
          aiDataCollectionEnabled: runtimeEditionConfig.aiDataCollection,
        });
        const { matchedNodes, matchedKeys, parentIdsWithMatches } = searchTreeNodes(visibleTreeData, value);
        treeStore.setSearchResult(matchedNodes);
        treeStore.setSearchResultKeys(matchedKeys);
        treeStore.setExpandedKeys([...parentIdsWithMatches, ...treeStore.expandedKeys]);
      }, 300),
      [],
    );

    useUpdateEffect(() => {
      debouncedSearch();
      return () => debouncedSearch.cancel();
    }, [searchBarValue, hiddenTreeNodeIds, debouncedSearch]);

    useEffect(() => {
      if (!active) {
        return;
      }

      const searchArea = document.getElementById('tree-search-area');
      const handleKeyDown = (event: KeyboardEvent) => {
        if (isShortcutEventMatch(event, shortcutConfig[ShortcutAction.WorkspaceTreeSearch].binding)) {
          event.preventDefault();
          searchBarRef.current?.focus?.();
        }
      };

      searchArea?.addEventListener('keydown', handleKeyDown);
      return () => {
        searchArea?.removeEventListener('keydown', handleKeyDown);
      };
    }, [active, shortcutConfig]);

    const showAddDatasourceBar = useMemo(() => {
      return isAdmin && !isEmbedIframe;
    }, [isAdmin, isEmbedIframe]);

    const showTreeSetting = useMemo(() => {
      return !isEmbedIframe;
    }, [isEmbedIframe]);

    return (
      <div>
        <div className={styles.searchRow}>
          <SearchBar
            ref={searchBarRef}
            className={styles.searchBar}
            searchAreaId="tree-search-area"
            placeholder={i18n('common.text.search')}
            value={searchBarValue}
            onChange={searchBarOnChange}
            suffix={
              <span className={styles.searchMatchCount}>
                {searchBarValue && searchResultKeys ? searchResultKeys.length : null}
              </span>
            }
          />
        </div>
        <div className={styles.workspaceLeftActionBar}>
          {showAddDatasourceBar && <AddDatasourceBar />}
          {buttonList.map((item) => {
            if (item.isHidden) {
              return null;
            }
            return (
              <Tooltip title={item.label} mouseEnterDelay={1} key={item.key}>
                <IconButton size="sm" onClick={item.onClick} code={item.icon} />
              </Tooltip>
            );
          })}
          <div className={styles.rightActions}>
            {onLocateActiveTab && (
              <Tooltip title={i18n('workspace.tips.locateActiveTab')} mouseEnterDelay={1}>
                <span>
                  <IconButton
                    size="sm"
                    code="icon-miaozhun"
                    disabled={locateActiveTabDisabled}
                    onClick={onLocateActiveTab}
                  />
                </span>
              </Tooltip>
            )}
            {showTreeSetting && <TreeSetting />}
          </div>
        </div>
      </div>
    );
  },
);

export default WorkspaceLeftActionBar;
