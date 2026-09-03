/*
 *Hook for canceling requests
 */

import { useCallback, useMemo, useState } from 'react';
import { isDesktop } from '@/utils/env';
import {
  DesktopRequestOptions,
  DesktopAbortControllerSignalParams,
  rejectCommandLineRequest,
} from '@/service/commandLine/commandLine';

type UseDiscardRequestReturnType = [(() => AbortSignal) | (() => DesktopRequestOptions['signal']), () => void];

type UseDiscardRequestType = () => UseDiscardRequestReturnType;

const useAbortRequest: UseDiscardRequestType = () => {
  // Web page cancellation request
  const [fetchController, setFetchController] = useState<AbortController | null>(null);
  // Desktop Cancel Request
  const [desktopController, setDesktopController] = useState<DesktopAbortControllerSignalParams | null>(null);

  const abortRequest = useCallback(() => {
    if (isDesktop) {
      if (!desktopController) return;
      rejectCommandLineRequest(desktopController.id, { message: 'signal is aborted without reason' });
      return;
    }

    // Use the native method to cancel the fetch request
    fetchController?.abort();
    setFetchController(new AbortController()); // Recreate a new AbortController instance
  }, [fetchController, desktopController]);

  // The build request on the desktop side will pass the request id and resolve method to cancel the request.
  // const desktopControllerSignal = useCallback((params: DesktopAbortControllerSignalParams) => {
  //   setDesktopController(params);
  // }, []);

  const initSignal = useMemo(() => {
    if (isDesktop) {
      return () => setDesktopController;
    }

    return () => {
      const _fetchController = new AbortController();
      setFetchController(_fetchController);
      return _fetchController.signal;
    };
  }, []);

  return [initSignal, abortRequest];
};

export default useAbortRequest;

// How to use
// const [signal, abortRequest] = useAbortRequest();
// Pass signal to the signal in the second parameter of the request
// xxxServer.xxx(params, { signal })
//  .then((res) => {
//    console.log(res);
//  })
//  .catch(reject);
// Click Cancel Request
// <div onClick={abortRequest}>Cancel request</div>

// Use multiple hook instances when a page needs to cancel requests independently.
