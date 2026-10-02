import { useLayoutEffect, useRef } from 'react';

interface InitialWorkspaceQuery {
  queryCurUser: () => Promise<unknown>;
  queryOrgList: () => Promise<unknown>;
  getGlobalData: () => void;
}

export const runInitialWorkspaceQuery = async ({
  queryCurUser,
  queryOrgList,
  getGlobalData,
}: InitialWorkspaceQuery): Promise<void> => {
  try {
    await Promise.all([queryCurUser(), queryOrgList()]);
    getGlobalData();
  } catch {
    // Initialization is fail-open so the application can render its recovery UI.
  }
};

export const useRunOnceWhenReady = (isReady: boolean, run: () => void): void => {
  const initializationStartedRef = useRef(false);

  useLayoutEffect(() => {
    if (!isReady || initializationStartedRef.current) {
      return;
    }
    initializationStartedRef.current = true;
    run();
  }, [isReady, run]);
};
