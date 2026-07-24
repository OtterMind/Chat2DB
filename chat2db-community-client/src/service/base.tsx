import { ErrorCodesWithoutToast } from '@/constants/request';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { useGlobalStore } from '@/store/global';
import { isDesktop } from '@/utils/env';
import { staticMessage } from '@chat2db/ui';
import request, { ResponseError } from 'umi-request';
import { commandLineRequest, DesktopRequestOptions } from './commandLine/commandLine';
import interceptorsResponse from './interceptorsResponse';

export type IErrorLevel = 'toast' | 'notification' | 'prompt' | 'critical' | false;
export type PermissionError = 'apply' | false;

export interface IOptions {
  method?: 'get' | 'post' | 'put' | 'delete';
  mock?: boolean;
  errorLevel?: IErrorLevel;
  permissionError?: PermissionError;
  // Defaults to true. SQL execution may disable the timeout explicitly.
  timeout?: boolean;
  delayTime?: number | true;
  isFullPath?: boolean;
  dynamicUrl?: boolean;
  contentType?: string; // Content-Type used to set request headers
  fullResponse?: boolean;
}

const errorHandler = (error: ResponseError, errorLevel: IErrorLevel) => {
  const { response } = error;
  if (!response) return;
  const errorText = response.statusText;
  const { status } = response;
  if (errorLevel === 'toast') {
    staticMessage.error(`${status}: ${errorText}`);
  }
};

// const request = extend({
//   // prefix: '/api',
//   Credentials: 'include', // Whether to bring cookies with the default request
//   headers: {
//     'Content-Type': 'application/json',
//     Accept: 'application/json',
//   },
// });

request.interceptors.request.use((url, options) => {
  const language = useGlobalStore.getState().baseSetting.language;
  return {
    options: {
      ...options,
      headers: {
        ...options.headers,
        'Accept-Language': language,
        'Time-Zone': new Intl.DateTimeFormat().resolvedOptions().timeZone,
      },
    },
  };
});

// response interceptor, handles response
request.interceptors.response.use(async (response, _options) => {
  try {
    if (isDesktop) {
      const Chat2db = response.headers.get('Chat2db') || '';
      if (Chat2db) {
        localStorage.setItem(runtimeEditionConfig.desktopResponseHeaderStorageKey, Chat2db);
      }
    }
  } catch (error) {
    console.error('response error', error);
  }
  return response;
});

export default function createRequest<P = void, R = void>(url: string, options?: IOptions) {
  const { method = 'get', isFullPath, dynamicUrl, contentType, fullResponse = false } = options || {};
  const { errorLevel: initialErrorLevel = 'notification', timeout = true, permissionError = 'apply' } = options || {};
  return function (
    params: P,
    restParams?: {
      signal: AbortSignal | DesktopRequestOptions['signal'] | null;
    },
  ) {
    const paramsInUrl: string[] = [];
    const effectiveErrorLevel = params?.errorLevel !== undefined ? params.errorLevel : initialErrorLevel;

    const _url = url.replace(/:(.+?)\b/, (_, name: string) => {
      const value = params[name];
      paramsInUrl.push(name);
      return `${value}`;
    });

    if (paramsInUrl.length) {
      paramsInUrl.forEach((name) => {
        delete params[name];
      });
    }

    if (isDesktop) {
      return commandLineRequest<R>(
        {
          requestUrl: _url,
          method,
          message: params,
        },
        {
          errorLevel: effectiveErrorLevel,
          permissionError,
          timeout,
          fullResponse,
          restParams: restParams as DesktopRequestOptions,
        },
      );
    } else {
      return new Promise<R>((resolve, reject) => {
        let dataName = '';
        switch (method) {
          case 'get':
            dataName = 'params';
            break;
          case 'delete':
            dataName = 'params';
            break;
          case 'post':
            dataName = 'data';
            break;
          case 'put':
            dataName = 'data';
            break;
          default:
            dataName = 'params';
            break;
        }

        let eventualUrl = _url;
        eventualUrl = isFullPath ? url : eventualUrl;

        // dynamic url
        if (dynamicUrl) {
          eventualUrl = params as string;
        }

        const requestOptions: any = {
          credentials: 'include', // Whether to bring cookies with the default request
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
          },
          [dataName]: params,
          ...restParams,
        };

        if (contentType === 'formData') {
          // Use FormData to handle file uploads
          const formData = new FormData();

          // Add files to FormData
          formData.append('file', params.file);

          Object.keys(params).forEach((key) => {
            if (key !== 'file') {
              formData.append(key, params[key]);
            }
          });

          // Remove params or data in requestOptions

          delete requestOptions[dataName];

          // Modify requestOptions and make sure to use FormData
          requestOptions.body = formData;

          delete requestOptions.headers['Content-Type'];
        }

        request[method](eventualUrl, requestOptions)
          .then((res: any) => {
            if (!res) return;
            // Deconstruct the returned data
            const { success, errorCode, errorMessage, errorDetail, solutionLink, data } = res;
            // If the request is successful
            if (success) {
              resolve(fullResponse ? res : data);
              return;
            }
            // If the request fails
            reject({
              errorCode: errorCode,
              errorMessage: errorMessage,
            });
            // If there is no need to pop up the toast error code
            if (ErrorCodesWithoutToast.includes(errorCode)) {
              const { errorCode: responseErrorCode, errorMessage: responseErrorMessage } = res;
              interceptorsResponse({
                errorCode: responseErrorCode,
                errorMessage: responseErrorMessage,
                requestParams: params,
                errorLevel: effectiveErrorLevel,
                permissionError,
              });
              return;
            }
            // Handle errors based on errorLevel
            switch (effectiveErrorLevel) {
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
                  requestParams: JSON.stringify(params),
                });
                break;
              default:
                break;
            }
          })
          .catch((error) => {
            errorHandler(error, effectiveErrorLevel);
            reject(error);
          });
      });
    }
  };
}
