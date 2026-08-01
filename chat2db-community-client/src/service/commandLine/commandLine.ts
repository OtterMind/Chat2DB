import { v4 as uuidv4 } from 'uuid';
import { useGlobalStore } from '@/store/global';
import { ServiceStatus } from '@/constants/common';
import { ErrorCodesWithoutToast } from '@/constants/request';
import interceptorsResponse from '../interceptorsResponse';
import { IErrorLevel, PermissionError } from '@/service/base';
import { staticMessage } from '@chat2db/ui';
import { createSafeIpcDebugMetadata } from './safeDebug';

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
  signal: (params: DesktopAbortControllerSignalParams) => void;
}

export interface IOptions {
  errorLevel: IErrorLevel;
  permissionError: PermissionError;
  // Whether a timeout is required, the default is true, currently only needs to be set to false when executing sql
  timeout?: boolean;
  fullResponse?: boolean;
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
}

// Interface timeout
export const TIMEOUT = 300000;
const pendingRequests = new Map<string, ICommandLineRequestListItem>();
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
      console.log(
        '%cCHAT2DB_IPC_REQUEST',
        'color: #00008B',
        createSafeIpcDebugMetadata(id, data.method, data.requestUrl, data.message),
      );
    }
    // Prepare for a cancellation request
    options?.restParams?.signal?.({ id, reject });
    let requestTimeoutTimer: any = null;

    if (options.timeout) {
      requestTimeoutTimer = setTimeout(() => {
        const item = pendingRequests.get(id);
        if (item) {
          pendingRequests.delete(id);
          reject?.(`timeout_error:${item.requestData.requestUrl}`);
        }
      }, TIMEOUT);
    }

    const commandLineRequestListItem = {
      requestData: {
        actionType: 'execute',
        headers: {},
        uuid: id,
        requestUrl: data.requestUrl,
        method: data.method,
        message: options.permissionError === 'apply' ? data.message : undefined,
      },
      responseData: null,
      requestTimeoutTimer,
      resolve,
      reject,
      options,
    };
    pendingRequests.set(id, commandLineRequestListItem);
    if (typeof window.javaQuery === 'function') {
      window.javaQuery({
        request: JSON.stringify(res),
        onSuccess: function (_data) {
          // console.log('%cCHAT2DB_IPC_RESPONSE', 'color: #B8860B', _data);
          pushMessageFlow(_data);
        },
        onFailure: function (error_code, error_message) {
          pendingRequests.delete(id);
          alert(error_message);
          reject(error_message);
        },
      });
    } else {
      pendingRequests.delete(id);
      reject(new Error("JCEF's javaQuery is not available"));
    }
  });
};

// Accept command line return
export const pushMessageFlow = (_data) => {
  const data = JSON.parse(_data);
  if (__PRINT_LOGS__ || window._PRINT_LOGS) {
    console.log('%cCHAT2DB_IPC_RESPONSE', 'color: #B8860B', {
      requestId: typeof data?.uuid === 'string' ? data.uuid : 'service-status',
      success: data?.message?.success === true,
      errorCode: data?.message?.errorCode,
    });
  }
  const { setServiceStatus } = useGlobalStore.getState();

  // Special handling application startup
  if (data === 'CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS') {
    setServiceStatus(ServiceStatus.SUCCESS);
    return;
  }

  // Only process logged requests
  if (data?.uuid && pendingRequests.has(data.uuid)) {
    const { message: messageData, uuid } = data;

    const { errorCode, success, errorMessage, errorDetail, solutionLink, eventualUrl } = messageData || {};

    const { resolve, reject, options, requestData, requestTimeoutTimer } = pendingRequests.get(uuid)!;
    pendingRequests.delete(uuid);

    // Clear timeout timer
    if (requestTimeoutTimer) {
      clearTimeout(requestTimeoutTimer);
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
            requestParams: JSON.stringify({
              requestUrl: requestData.requestUrl,
              method: requestData.method,
            }),
          });
          break;
        default:
          break;
      }
    }
    // Remove request record
  }
};

// response interception
export const responseInterceptor = (response, requestData, options) => {
  const { errorCode, errorMessage } = response || {};
  const { message } = requestData || {};
  const { errorLevel, permissionError } = options;
  interceptorsResponse({ errorCode, errorMessage, requestParams: message, errorLevel, permissionError });
};
