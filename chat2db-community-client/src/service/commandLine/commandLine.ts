import { v4 as uuidv4 } from 'uuid';
import { useGlobalStore } from '@/store/global';
import { ServiceStatus } from '@/constants/common';
import { ErrorCodesWithoutToast } from '@/constants/request';
import interceptorsResponse from '@/service/interceptorsResponse';
import { IErrorLevel, PermissionError } from '@/service/base';
import { staticMessage } from '@chat2db/ui';

export interface ICommandLineRequest {
  requestUrl: string;
  method: string;
  message: any;
}

export interface ICommandLineParams extends ICommandLineRequest {
  uuid: string;
}

export interface DesktopAbortControllerSignalParams {
  id: string;
  reject: any;
}

export interface DesktopRequestOptions {
  signal?: AbortSignal | ((params: DesktopAbortControllerSignalParams) => void) | null;
}

export interface IOptions {
  errorLevel: IErrorLevel;
  permissionError: PermissionError;
  // Whether a timeout is required, the default is true, currently only needs to be set to false when executing sql
  timeout?: boolean;
  fullResponse?: boolean;
  // Typed clients own response validation and business errors in this mode.
  rawResponse?: boolean;
  // The second parameter of the request
  restParams?: DesktopRequestOptions;
}

export interface ICommandLineRequestListItem {
  requestData: ICommandLineParams;
  responseData: any;
  requestTimeoutTimer: any;
  resolve: (value: any) => void;
  reject: (reason?: any) => void;
  options: IOptions;
  abortCleanup?: () => void;
}

// Interface timeout
export const TIMEOUT = 300000;
// TODO: must be deleted
// window._PRINT_LOGS = true;

// JCEF request requestJCEF
export const commandLineRequest = <R>(data: ICommandLineRequest, options: IOptions) => {
  const language = useGlobalStore.getState().baseSetting.language;
  const id = uuidv4();

  const commandLineParams = {
    actionType: 'execute',
    headers: {
      'Accept-Language': language,
      'Time-Zone': new Intl.DateTimeFormat().resolvedOptions().timeZone,
    },
    uuid: id,
    ...data,
  };
  return new Promise<R>((resolve, reject) => {
    const res = JSON.parse(
      JSON.stringify(commandLineParams, (key, value) => {
        // Remove functions and undefined properties
        if (typeof value === 'function' || value === undefined) {
          return undefined;
        }
        return value;
      }),
    );
    if (__PRINT_LOGS__ || window._PRINT_LOGS) {
      console.log('%cCHAT2DB_IPC_REQUEST', 'color: #00008B', JSON.stringify(res));
    }
    const signal = options?.restParams?.signal;
    const abortSignal = typeof signal === 'function' ? undefined : signal;
    let abortCleanup: (() => void) | undefined;
    const rejectAborted = () => reject(abortSignal?.reason);
    if (typeof signal === 'function') {
      signal({ id, reject });
    } else if (abortSignal) {
      if (abortSignal.aborted) {
        rejectAborted();
        return;
      }
      const onAbort = () => {
        const item = useGlobalStore.getState().commandLineRequestList[id];
        if (!item) return;
        if (item.requestTimeoutTimer) clearTimeout(item.requestTimeoutTimer);
        item.abortCleanup?.();
        useGlobalStore.getState().removeCommandLineRequestListItem(id);
        rejectAborted();
      };
      abortSignal.addEventListener('abort', onAbort, { once: true });
      abortCleanup = () => abortSignal.removeEventListener('abort', onAbort);
    }
    let requestTimeoutTimer: any = null;

    if (options.timeout) {
      requestTimeoutTimer = setTimeout(() => {
        const item = useGlobalStore.getState().commandLineRequestList[id];
        if (item) {
          useGlobalStore.getState().removeCommandLineRequestListItem(id);
          abortCleanup?.();
          reject?.(`timeout_error:${item.requestData.requestUrl}`);
        }
      }, TIMEOUT);
    }

    const commandLineRequestListItem = {
      requestData: commandLineParams,
      responseData: null,
      requestTimeoutTimer,
      resolve,
      reject,
      options,
      abortCleanup,
    };
    useGlobalStore.getState().addCommandLineRequestListItem(commandLineRequestListItem);
    const fail = (error: unknown) => {
      if (!useGlobalStore.getState().commandLineRequestList[id]) return;
      if (requestTimeoutTimer) clearTimeout(requestTimeoutTimer);
      abortCleanup?.();
      useGlobalStore.getState().removeCommandLineRequestListItem(id);
      reject(error);
    };
    try {
      if (typeof window.javaQuery !== 'function') {
        fail(new Error("JCEF's javaQuery is not available!"));
        return;
      }
      window.javaQuery({
        request: JSON.stringify(res),
        onSuccess: function (_data) {
          // console.log('%cCHAT2DB_IPC_RESPONSE', 'color: #B8860B', _data);
          pushMessageFlow(_data);
        },
        onFailure: function (error_code, error_message) {
          if (!useGlobalStore.getState().commandLineRequestList[id]) return;
          fail(error_message);
          if (!options.rawResponse) alert(error_message);
          console.log('error', error_message);
        },
      });
    } catch (error) {
      fail(error);
    }
  });
};

// Accept command line return
export const pushMessageFlow = (_data) => {
  const data = JSON.parse(_data);
  if (__PRINT_LOGS__ || window._PRINT_LOGS) {
    console.log('%cCHAT2DB_IPC_RESPONSE', 'color: #B8860B', new Date().toISOString(), data);
  }
  const { setServiceStatus, commandLineRequestList, removeCommandLineRequestListItem } = useGlobalStore.getState();

  // Special handling application startup
  if (data === 'CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS') {
    setServiceStatus(ServiceStatus.SUCCESS);
    return;
  }

  // Only process logged requests
  if (data?.uuid && commandLineRequestList?.[data.uuid]) {
    const { message: messageData, uuid } = data;

    const { errorCode, success, errorMessage, errorDetail, solutionLink, eventualUrl } = messageData || {};

    const { resolve, reject, options, requestData, requestTimeoutTimer, abortCleanup } = commandLineRequestList[uuid];

    // Clear timeout timer
    if (requestTimeoutTimer) {
      clearTimeout(requestTimeoutTimer);
    }
    abortCleanup?.();
    removeCommandLineRequestListItem(uuid);

    if (options.rawResponse) {
      resolve(messageData);
      return;
    }

    // response interception
    responseInterceptor(messageData, requestData, options);
    // Process request results
    if (success) {
      resolve?.(options.fullResponse ? messageData : messageData?.data);
    } else {
      reject({
        errorCode: errorCode,
        errorMessage: errorMessage,
      });
      // If there is no need to pop up the toast error code
      if (ErrorCodesWithoutToast.includes(errorCode)) {
        return;
      }
      switch (options.errorLevel) {
        case 'toast':
          staticMessage.error(errorMessage);
          break;
        case 'notification':
          useGlobalStore?.getState()?.systemErrorMessageApi?.({
            errorCode,
            errorMessage,
            errorDetail,
            solutionLink,
            requestUrl: eventualUrl,
            requestParams: JSON.stringify(requestData),
          });
          break;
        default:
          break;
      }
    }
  }
};

// response interception
export const responseInterceptor = (response, requestData, options) => {
  const { errorCode, errorMessage } = response || {};
  const { message } = requestData || {};
  const { errorLevel, permissionError } = options;
  interceptorsResponse({ errorCode, errorMessage, requestParams: message, errorLevel, permissionError });
};
