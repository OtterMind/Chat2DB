import assert from 'node:assert/strict';
import { act, StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';

import { runInitialWorkspaceQuery, useRunOnceWhenReady } from './initQueryLifecycle';

const dom = new JSDOM('<div id="root"></div>');
Object.defineProperties(globalThis, {
  window: { configurable: true, value: dom.window },
  document: { configurable: true, value: dom.window.document },
  navigator: { configurable: true, value: dom.window.navigator },
  IS_REACT_ACT_ENVIRONMENT: { configurable: true, value: true },
});

const main = async () => {
  let runCount = 0;
  const Probe = ({ ready, renderToken }: { ready: boolean; renderToken: number }) => {
    useRunOnceWhenReady(ready, () => {
      runCount += 1;
      void renderToken;
    });
    return null;
  };

  const container = document.getElementById('root');
  assert.ok(container);
  const root = createRoot(container);

  await act(async () => {
    root.render(
      <StrictMode>
        <Probe ready={false} renderToken={0} />
      </StrictMode>,
    );
  });
  assert.equal(runCount, 0, 'initialization must wait for app configuration');

  await act(async () => {
    root.render(
      <StrictMode>
        <Probe ready renderToken={0} />
      </StrictMode>,
    );
  });
  assert.equal(runCount, 1, 'StrictMode effect replay must still initialize once');

  await act(async () => {
    root.render(
      <StrictMode>
        <Probe ready renderToken={1} />
      </StrictMode>,
    );
  });
  assert.equal(runCount, 1, 'unrelated rerenders must not repeat initialization');

  await act(async () => root.unmount());

  const successfulCalls: string[] = [];
  await runInitialWorkspaceQuery({
    queryCurUser: async () => {
      successfulCalls.push('user');
    },
    queryOrgList: async () => {
      successfulCalls.push('org');
    },
    getGlobalData: () => successfulCalls.push('global'),
  });
  assert.deepEqual(successfulCalls, ['user', 'org', 'global']);

  let globalDataCalls = 0;
  await runInitialWorkspaceQuery({
    queryCurUser: async () => {
      throw new Error('identity unavailable');
    },
    queryOrgList: async () => undefined,
    getGlobalData: () => {
      globalDataCalls += 1;
    },
  });
  assert.equal(globalDataCalls, 0, 'failed identity or organization queries must remain fail-open without partial load');

  console.log('Init query lifecycle tests passed.');
};

void main();
