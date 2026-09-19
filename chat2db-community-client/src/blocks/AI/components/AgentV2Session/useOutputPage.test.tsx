import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import type { AgentOutputPage, AgentOutputQuery } from '@/types/agentOutput';
import type { LoadOutputPage } from './useOutputPage';

const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>');
Object.defineProperties(globalThis, {
  window: { configurable: true, value: dom.window },
  document: { configurable: true, value: dom.window.document },
  navigator: { configurable: true, value: dom.window.navigator },
  IS_REACT_ACT_ENVIRONMENT: { configurable: true, value: true },
});

async function main() {
  const [{ createElement, act }, { createRoot }, { default: useOutputPage }] = await Promise.all([
    import('react'), import('react-dom/client'), import('./useOutputPage'),
  ]);
  const requests: Array<{
    sessionId: string; artifactId: string; query: AgentOutputQuery; signal: AbortSignal;
    resolve: (page: AgentOutputPage) => void; reject: (error: Error) => void;
  }> = [];
  const load: LoadOutputPage = (sessionId, artifactId, query, signal) => new Promise((resolve, reject) => {
    requests.push({ sessionId, artifactId, query, signal, resolve, reject });
  });
  function Viewer({ sessionId, query }: { sessionId: string; query: AgentOutputQuery }) {
    const { state, cancel } = useOutputPage(sessionId, 'artifact', query, load);
    return createElement('button', { 'data-status': state.status, onClick: cancel },
      state.status === 'ready' ? state.page.content : state.status);
  }
  const root = createRoot(document.getElementById('root')!);
  const render = async (sessionId: string, query: AgentOutputQuery) => {
    await act(async () => root.render(createElement(Viewer, { sessionId, query })));
  };
  const body = () => document.querySelector('button')!;
  const page = (content: string): AgentOutputPage => ({ content, hasMore: false });

  await render('first-session', {});
  assert.equal(body().dataset.status, 'loading');
  await render('first-session', { pattern: 'new search' });
  assert.equal(requests[0].signal.aborted, true);
  await act(async () => requests[1].resolve(page('search matches')));
  assert.equal(body().textContent, 'search matches');
  // A request transport may resolve even after cancellation; it must not replace the new result.
  await act(async () => requests[0].resolve(page('stale full content')));
  assert.equal(body().textContent, 'search matches');

  await render('second-session', { cursor: 'next' });
  assert.equal(requests[1].signal.aborted, true);
  assert.equal(body().textContent, 'loading', 'Switching sessions must immediately hide the previous result');
  await act(async () => body().dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })));
  assert.equal(requests[2].signal.aborted, true);
  assert.equal(body().dataset.status, 'idle');
  await act(async () => requests[2].resolve(page('cancelled content')));
  assert.equal(body().dataset.status, 'idle');

  await render('second-session', {});
  await act(async () => requests[3].reject(new Error('file unavailable')));
  assert.equal(body().dataset.status, 'failed');
  await render('second-session', {});
  await act(async () => requests[4].resolve(page('recovered content')));
  assert.equal(body().textContent, 'recovered content');
  await render('second-session', { pattern: 'pending at close' });
  await act(async () => root.unmount());
  assert.equal(requests[5].signal.aborted, true);
  await act(async () => requests[5].resolve(page('after unmount')));
  dom.window.close();
  console.log('Output reading tests passed: concurrent searches, session isolation, cancel, failure, retry and unmount');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
