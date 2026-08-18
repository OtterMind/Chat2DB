import { useCallback } from 'react';
import { useUpdateEffect } from 'ahooks';
import { debounce } from 'lodash';

import useRuntimeEditionCapabilities from '@/hooks/useRuntimeEditionCapabilities';
import { useTreeStore } from '@/store/tree';
import { searchTreeNodes } from '@/utils';
import { filterTreeNodesForDisplay } from '@/utils/filterTreeNodes';
import WorkspaceHeaderSearch from '../WorkspaceHeaderSearch';

const WorkspaceTreeSearch = () => {
  const { aiDataCollection } = useRuntimeEditionCapabilities();
  const { searchBarValue, setSearchBarValue, searchResultKeys, hiddenTreeNodeIds } = useTreeStore((state) => ({
    searchBarValue: state.searchBarValue,
    setSearchBarValue: state.setSearchBarValue,
    searchResultKeys: state.searchResultKeys,
    hiddenTreeNodeIds: state.hiddenTreeNodeIds,
  }));
  const closeSearch = useCallback(() => {
    const treeStore = useTreeStore.getState();
    treeStore.setSearchResult(null);
    treeStore.setSearchResultKeys(null);
  }, []);

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
        aiDataCollectionEnabled: aiDataCollection,
      });
      const { matchedNodes, matchedKeys, parentIdsWithMatches } = searchTreeNodes(visibleTreeData, value);
      treeStore.setSearchResult(matchedNodes);
      treeStore.setSearchResultKeys(matchedKeys);
      treeStore.setExpandedKeys([...parentIdsWithMatches, ...treeStore.expandedKeys]);
    }, 300),
    [aiDataCollection],
  );

  useUpdateEffect(() => {
    debouncedSearch();
    return () => debouncedSearch.cancel();
  }, [searchBarValue, hiddenTreeNodeIds, debouncedSearch]);

  return (
    <WorkspaceHeaderSearch
      matchCount={searchBarValue && searchResultKeys ? searchResultKeys.length : undefined}
      onChange={setSearchBarValue}
      onClose={closeSearch}
      value={searchBarValue}
    />
  );
};

export default WorkspaceTreeSearch;
