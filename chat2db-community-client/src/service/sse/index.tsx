import { useGlobalStore } from '@/store/global';
import { JcefEventBus } from '@/jcef/eventBus';
import { v4 as uuidv4 } from 'uuid';
import { dispatchDesktopStreamRequest } from './requestFailure';

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
    console.log('%cCHAT2DB_IPC_REQUEST-SSE', 'color: #FF0000', new Date().toISOString(), commandLineParams);
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

  dispatchDesktopStreamRequest({
    requestId,
    serializedRequest: JSON.stringify(res),
    javaQuery: typeof window.javaQuery === 'function' ? (request) => window.javaQuery(request) : undefined,
    publish: (eventName, output) => JcefEventBus.publish(eventName, output),
  });

  return {
    requestId,
  } as IJcefSseRequest;
};

export default sendClientSSERequest;
