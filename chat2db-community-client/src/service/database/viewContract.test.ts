import assert from 'node:assert/strict';
import requestModule from 'umi-request';

declare const require: {
  extensions?: Record<string, (module: { exports: string }, filename: string) => void>;
  (moduleName: string): unknown;
};

if (typeof require !== 'undefined' && require.extensions) {
  ['.css', '.less', '.svg', '.ttf', '.woff', '.woff2'].forEach((extension) => {
    require.extensions![extension] = (module) => {
      module.exports = '';
    };
  });
}

const moduleLoader = require('node:module') as {
  _load: (request: string, parent: unknown, isMain: boolean) => unknown;
};
const originalLoad = moduleLoader._load;
moduleLoader._load = (request, parent, isMain) => {
  if (request === '@chat2db/ui') {
    return {
      staticMessage: { error: () => undefined },
      staticModal: { confirm: () => undefined },
    };
  }
  if (request === '@/store/global') {
    return {
      useGlobalStore: {
        getState: () => ({
          baseSetting: { language: 'en-US' },
          systemErrorMessageApi: () => undefined,
        }),
      },
    };
  }
  if (request === '@/utils/env') {
    return { isDesktop: false };
  }
  if (request === '@client-runtime') {
    return { clientRuntime: { desktopResponseHeaderStorageKey: 'Chat2db_Community_Test' } };
  }
  return originalLoad(request, parent, isMain);
};

const globalObject = globalThis as unknown as {
  __APP_VERSION__: string;
  __APP_NAME__: string;
  __APP_CAPITAL_NAME__: string;
  __APP_DISPLAY_NAME__: string;
  __APP_PROTOCOL_SCHEME__: string;
  __ENV__: string;
  __RUNTIME_ENV__: string;
  window: { javaQuery?: unknown };
  location: Pick<Location, 'search'>;
  document: {
    addEventListener: () => void;
    body: { firstChild: null; appendChild: () => void };
    createElement: () => {
      getElementsByTagName: () => Array<{ setAttribute: () => void; style: Record<string, unknown> }>;
      innerHTML: string;
    };
    getElementsByTagName: () => Array<{ getAttribute: () => null }>;
    readyState: string;
    removeEventListener: () => void;
    write: () => void;
  };
  matchMedia: (query: string) => Pick<MediaQueryList, 'matches' | 'media' | 'addEventListener' | 'removeEventListener'>;
  localStorage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
};

globalObject.__APP_VERSION__ = '0.0.0-test';
globalObject.__APP_NAME__ = 'chat2db-community-test';
globalObject.__APP_CAPITAL_NAME__ = 'Chat2DBCommunityTest';
globalObject.__APP_DISPLAY_NAME__ = 'Chat2DB Community Test';
globalObject.__APP_PROTOCOL_SCHEME__ = 'chat2db-community-test';
globalObject.__ENV__ = 'test';
globalObject.__RUNTIME_ENV__ = 'community';
globalObject.window = { javaQuery: undefined };
globalObject.location = { search: '' };
globalObject.document = {
  addEventListener: () => undefined,
  body: { firstChild: null, appendChild: () => undefined },
  createElement: () => ({
    getElementsByTagName: () => [{ setAttribute: () => undefined, style: {} }],
    innerHTML: '',
  }),
  getElementsByTagName: () => [{ getAttribute: () => null }],
  readyState: 'complete',
  removeEventListener: () => undefined,
  write: () => undefined,
};
globalObject.matchMedia = (query) => ({
  matches: false,
  media: query,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
});
globalObject.localStorage = {
  getItem: () => null,
  setItem: () => undefined,
  removeItem: () => undefined,
};

const request = requestModule as typeof requestModule;
const mutableRequest = request as unknown as {
  post: (url: string, options: unknown) => Promise<unknown>;
};

const capturedRequests: Array<{ url: string; options: any }> = [];
const originalPost = mutableRequest.post;

mutableRequest.post = async (url, options) => {
  capturedRequests.push({ url, options });
  return { success: true, data: null };
};

async function runTests() {
  const viewModule = await import('./view');
  const buildDropViewRequest = viewModule.buildDropViewRequest;
  assert.equal(typeof buildDropViewRequest, 'function', 'drop view exposes a viewName request builder');

  const payload = buildDropViewRequest({
    dataSourceId: 42,
    databaseName: 'analytics',
    schemaName: 'reporting',
    viewName: 'monthly_summary',
  });

  await viewModule.default.dropView(payload);

  assert.equal(capturedRequests.length, 1, 'dropView sends one API request');
  assert.equal(capturedRequests[0].url, '/api/rdb/view/drop');
  assert.deepEqual(capturedRequests[0].options.data, {
    dataSourceId: 42,
    databaseName: 'analytics',
    schemaName: 'reporting',
    viewName: 'monthly_summary',
  });
  assert.equal(
    Object.prototype.hasOwnProperty.call(capturedRequests[0].options.data, 'tableName'),
    false,
    'view drop payload must not send tableName to the view API',
  );
}

runTests()
  .then(() => {
    console.log('View API contract tests passed');
  })
  .finally(() => {
    moduleLoader._load = originalLoad;
    mutableRequest.post = originalPost;
  });
