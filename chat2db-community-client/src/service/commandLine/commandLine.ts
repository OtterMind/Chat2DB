import { v4 as uuidv4 } from 'uuid';
import { useGlobalStore } from '@/store/global';
import { ServiceStatus } from '@/constants/common';
import { ErrorCodesWithoutToast } from '@/constants/request';
import interceptorsResponse from '@/service/interceptorsResponse';
import { IErrorLevel, PermissionError } from '@/service/base';
import { staticMessage } from '@chat2db/ui';
import {
  cleanupTrackedCommandLineRequest,
  rejectTrackedCommandLineRequest,
  settleTrackedCommandLineResponse,
} from './requestSettlement';

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
// TODO: must be deleted
// window._PRINT_LOGS = true;

const commandLineRequestRegistry = {
  get: (id: string) => useGlobalStore.getState().commandLineRequestList[id],
  remove: (id: string) => useGlobalStore.getState().removeCommandLineRequestListItem(id),
  clearTimer: (timer: unknown) => clearTimeout(timer as ReturnType<typeof setTimeout>),
};

export const rejectCommandLineRequest = (id: string, reason: unknown) =>
  rejectTrackedCommandLineRequest(id, reason, commandLineRequestRegistry);

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
    // Prepare for a cancellation request
    options?.restParams?.signal?.({ id, reject });
    let requestTimeoutTimer: any = null;

    if (options.timeout) {
      requestTimeoutTimer = setTimeout(() => {
        const item = useGlobalStore.getState().commandLineRequestList[id];
        if (item) {
          cleanupTrackedCommandLineRequest(id, commandLineRequestRegistry);
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
    };
    useGlobalStore.getState().addCommandLineRequestListItem(commandLineRequestListItem);
    if (typeof window.javaQuery === 'function') {
      try {
        window.javaQuery({
          request: JSON.stringify(res),
          onSuccess: function (_data) {
            // console.log('%cCHAT2DB_IPC_RESPONSE', 'color: #B8860B', _data);
            pushMessageFlow(_data);
          },
          onFailure: function (error_code, error_message) {
            try {
              alert(error_message);
              console.log('error', error_code, error_message);
            } finally {
              rejectCommandLineRequest(id, error_message);
            }
          },
        });
      } catch (error) {
        rejectCommandLineRequest(id, error);
      }
    } else {
      const error = new Error("JCEF's javaQuery is not available");
      console.error(error.message);
      rejectCommandLineRequest(id, error);
    }
  });
};

// Accept command line return
export const pushMessageFlow = (_data) => {
  const data = JSON.parse(_data);
  if (__PRINT_LOGS__ || window._PRINT_LOGS) {
    console.log('%cCHAT2DB_IPC_RESPONSE', 'color: #B8860B', new Date().toISOString(), data);
  }
  const { setServiceStatus } = useGlobalStore.getState();

  // Special handling application startup
  if (data === 'CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS') {
    setServiceStatus(ServiceStatus.SUCCESS);
    return;
  }

  // Only process logged requests
  if (data?.uuid) {
    const { message: messageData, uuid } = data;
    settleTrackedCommandLineResponse({
      requestId: uuid,
      message: messageData || {},
      registry: commandLineRequestRegistry,
      beforeSettle: (request, message) => responseInterceptor(message, request.requestData, request.options),
      suppressErrorReport: (errorCode) => ErrorCodesWithoutToast.includes(errorCode as any),
      reportError: (request, message) => {
        switch (request.options.errorLevel) {
          case 'toast':
            staticMessage.error(message.errorMessage);
            break;
          case 'notification':
            useGlobalStore?.getState()?.systemErrorMessageApi?.({
              errorCode: message.errorCode,
              errorMessage: message.errorMessage,
              errorDetail: message.errorDetail,
              solutionLink: message.solutionLink,
              requestUrl: message.eventualUrl,
              requestParams: JSON.stringify(request.requestData),
            });
            break;
          default:
            break;
        }
      },
    });
  }
};

// response interception
export const responseInterceptor = (response, requestData, options) => {
  const { errorCode, errorMessage } = response || {};
  const { message } = requestData || {};
  const { errorLevel, permissionError } = options;
  interceptorsResponse({ errorCode, errorMessage, requestParams: message, errorLevel, permissionError });
};
