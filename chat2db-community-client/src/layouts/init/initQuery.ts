import { useGlobalStore } from '@/store/global';
import { useOrgStore } from '@/store/workspaceContext';
import { useUserStore } from '@/store/session';
import { removeOpenScreenAnimation } from '@/utils/dom';
import { useCallback, useState } from 'react';
import { runInitialWorkspaceQuery, useRunOnceWhenReady } from './initQueryLifecycle';
import useGlobalData from './useGlobalData';

const useInitQuery = () => {
  const [initQueryLoaded, setInitQueryLoaded] = useState(false);
  const getGlobalData = useGlobalData();
  const isReady = useGlobalStore((state) => state.appConfig.isReady);
  const queryCurUser = useUserStore((state) => state.queryCurUser);
  const queryOrgList = useOrgStore((state) => state.queryOrgList);

  const initialize = useCallback(() => {
    removeOpenScreenAnimation();
    void runInitialWorkspaceQuery({ queryCurUser, queryOrgList, getGlobalData })
      .finally(() => setInitQueryLoaded(true));
  }, [getGlobalData, queryCurUser, queryOrgList]);

  useRunOnceWhenReady(isReady, initialize);

  return { initQueryLoaded };
};

export default useInitQuery;
