import { EventSourcePolyfill } from 'event-source-polyfill';

interface ISSEConnectionOptions {
  url: string;
  uid: string;
}

export const getSSEBaseUrl = (): string => {
  const storedBaseURL = localStorage.getItem('_BaseURL');
  if (storedBaseURL) {
    return storedBaseURL;
  }
  if (location.href.indexOf('dist/index.html') > -1) {
    return `http://127.0.0.1:${__APP_PORT__ || '10824'}`;
  }
  const isDev = process.env.NODE_ENV === 'development';
  if (isDev) {
    return 'http://127.0.0.1:10821';
  }
  return location.origin;
};

export const createSSEConnection = ({ url, uid }: ISSEConnectionOptions) => {
  if (!url) {
    throw new Error('url is required');
  }

  const DBHUB = localStorage.getItem('DBHUB');
  return new EventSourcePolyfill(`${getSSEBaseUrl()}${url}`, {
    headers: {
      uid,
      DBHUB: DBHUB || '',
    },
    heartbeatTimeout: 12000000,
  });
};

export const createChatPayload = async (payload: Record<string, any>): Promise<string> => {
  const DBHUB = localStorage.getItem('DBHUB');
  const response = await fetch(`${getSSEBaseUrl()}/api/ai/chat/payload`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      DBHUB: DBHUB || '',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    throw new Error(`Create chat payload failed: ${response.status}`);
  }

  const data = await response.json();
  return data.payloadId;
};

export const cancelSSESession = async (sessionId: string): Promise<void> => {
  const DBHUB = localStorage.getItem('DBHUB');
  await fetch(`${getSSEBaseUrl()}/api/ai/chat/${sessionId}`, {
    method: 'DELETE',
    headers: {
      DBHUB: DBHUB || '',
    },
  });
};
