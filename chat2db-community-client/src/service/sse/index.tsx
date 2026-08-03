import { useGlobalStore } from '@/store/global';
import { v4 as uuidv4 } from 'uuid';
import { createSafeIpcDebugMetadata } from '@/service/commandLine/safeDebug';

export interface IJcefSseRequest {
  requestId: string;
}

const sendClientSSERequest = (baseURL, message) => {
  const language = useGlobalStore.getState().baseSetting.language;
  const requestId = uuidv4();
  const commandLineParams = {
    actionType: 'execute',
    headers: {
      'Accept-Language': language,
      'Time-Zone': new Intl.DateTimeFormat().resolvedOptions().timeZone,
    },
    uuid: requestId,
    method: 'post',
    requestUrl: baseURL,
    message,
  };

  if (__PRINT_LOGS__ || window._PRINT_LOGS) {
    console.log(
      '%cCHAT2DB_IPC_REQUEST-SSE',
      'color: #FF0000',
      createSafeIpcDebugMetadata(requestId, 'post', baseURL, message),
    );
  }

  // commandLineParams may include functions or circular references that cannot be serialized.
  const res = JSON.parse(
    JSON.stringify(commandLineParams, (key, value) => {
      // Remove functions and undefined properties
      if (typeof value === 'function' || value === undefined) {
        return undefined;
      }
      return value;
    }),
  );

  window.javaQuery({
    request: JSON.stringify(res),
    onSuccess: function () {},
    onFailure: function () {},
  });

  return {
    requestId,
  } as IJcefSseRequest;
};

export default sendClientSSERequest;
