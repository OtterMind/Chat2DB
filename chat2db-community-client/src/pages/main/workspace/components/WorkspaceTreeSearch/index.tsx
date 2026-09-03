import { useCallback } from 'react';
import { useUpdateEffect } from 'ahooks';
import { debounce } from 'lodash';

import { useTreeStore } from '@/store/tree';
import WorkspaceHeaderSearch from '../WorkspaceHeaderSearch';
import { resolveWorkspaceTreeSearch } from './lifecycle';

const WorkspaceTreeSearch = () => {
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
    treeStore.setSearchRequiredExpandedKeys([]);
  }, []);

  const debouncedSearch = useCallback(
    debounce(() => {
      const treeStore = useTreeStore.getState();
      const value = treeStore.regularSearchBarValue;
      if (!value) {
        treeStore.setSearchResult(null);
        treeStore.setSearchResultKeys(null);
        treeStore.setSearchRequiredExpandedKeys([]);
        return;
      }
      const searchState = resolveWorkspaceTreeSearch(
        treeStore.treeData || [],
        value,
        treeStore.hiddenTreeNodeIds,
        treeStore.invalidatedTreeNodeKeys,
      );
      treeStore.setSearchResult(searchState.matchedNodes);
      treeStore.setSearchResultKeys(searchState.matchedKeys);
      treeStore.setSearchRequiredExpandedKeys(searchState.requiredExpandedKeys);
    }, 300),
    [],
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
