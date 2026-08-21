import { useGlobalStore } from '@/store/global';
import { useOrgStore } from '@/store/workspaceContext';
import { useUserStore } from '@/store/session';
import { removeOpenScreenAnimation } from '@/utils/dom';
import { useLayoutEffect, useState } from 'react';
import useGlobalData from './useGlobalData';

const useInitQuery = () => {
  const [initQueryLoaded, setInitQueryLoaded] = useState(false);
  const getGlobalData = useGlobalData();
  const isReady = useGlobalStore((state) => state.appConfig.isReady);
  const queryCurUser = useUserStore((state) => state.queryCurUser);
  const queryOrgList = useOrgStore((state) => state.queryOrgList);

  useLayoutEffect(() => {
    if (!isReady) {
      return;
    }
    removeOpenScreenAnimation();
    Promise.all([queryCurUser(), queryOrgList()])
      .then(() => getGlobalData())
      .catch(() => undefined)
      .finally(() => setInitQueryLoaded(true));
  }, [getGlobalData, isReady, queryCurUser, queryOrgList]);

  return { initQueryLoaded };
};

export default useInitQuery;
