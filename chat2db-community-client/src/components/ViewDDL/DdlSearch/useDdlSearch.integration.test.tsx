/**
 * Gates 3+4 automation (Issue #2748, plan B1): focus, scope dispatch and the
 * full shortcut lifecycle of useDdlSearch, mounted for real under jsdom with
 * a polyfilled CSS Custom Highlight registry. DdlSearchBar is stubbed and a
 * real Zustand store replaces the app store so the @chat2db/ui barrel and
 * app config chain stay out of Node.
 *
 * What jsdom cannot prove (layout): getClientRects-based centring is covered
 * by pure-function tests instead; see ddlSearch.test.ts.
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import { JSDOM } from 'jsdom';

const globalObj = globalThis as unknown as Record<string, unknown>;
globalObj.__RUNTIME_ENV__ = 'community';
globalObj.__ENV__ = 'test';
globalObj.__APP_NAME__ = 'chat2db-community';
globalObj.__APP_CAPITAL_NAME__ = 'Chat2DB';
globalObj.__APP_DISPLAY_NAME__ = 'Chat2DB';
globalObj.__APP_PROTOCOL_SCHEME__ = 'chat2db';

const SQL_A = 'CREATE TABLE `user_info` (\n  `id` bigint NOT NULL\n);\nSELECT id FROM user_info;';
const SQL_B = 'CREATE VIEW `v_account` AS SELECT 1;';

interface HarnessController {
  searchOpen: boolean;
}

test('useDdlSearch: scope focus, highlight lifecycle and shortcut lifecycle', async () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', { pretendToBeVisual: true });
  const win = dom.window as unknown as Record<string, unknown>;
  for (const key of ['window', 'document', 'HTMLElement', 'Node', 'NodeFilter', 'MutationObserver', 'KeyboardEvent', 'MouseEvent']) {
    globalObj[key] = win[key];
  }
  Object.defineProperty(globalThis, 'navigator', {
    value: { userAgent: 'Mac' },
    configurable: true,
  });
  globalObj.requestAnimationFrame = win.requestAnimationFrame;
  globalObj.cancelAnimationFrame = win.cancelAnimationFrame;
  globalObj.IS_REACT_ACT_ENVIRONMENT = true;

  // Minimal CSS Custom Highlight polyfill (jsdom has none).
  const highlightRegistry = new Map<string, { ranges: Range[] }>();
  class HighlightPolyfill {
    ranges: Range[];
    constructor(...ranges: Range[]) {
      this.ranges = ranges;
    }
  }
  (win as any).Highlight = HighlightPolyfill;
  (win as any).CSS = { highlights: highlightRegistry };
  globalObj.Highlight = HighlightPolyfill;
  globalObj.CSS = (win as any).CSS;

  const React = await import('react');

  // CJS require hook: stub DdlSearchBar (pulls the @chat2db/ui barrel, which
  // imports .webp assets) and the global store (pulls build-time defines).
  const barStub = {
    focusCalls: { count: 0 },
    lastProps: null as any,
  };
  const DdlSearchBarStub = React.forwardRef((props: any, ref: any) => {
    barStub.lastProps = props;
    React.useImperativeHandle(ref, () => ({
      focus: () => {
        barStub.focusCalls.count += 1;
      },
    }));
    return React.createElement('div', { 'data-ddl-search-bar-stub': 'true' });
  });
  const { create } = await import('zustand');
  const useGlobalStoreStub = create<{ shortcutOverrides: Record<string, { binding: string | null }> }>(() => ({
    shortcutOverrides: {},
  }));

  const nodeModule = (await import('node:module')) as any;
  const originalLoad = nodeModule.Module._load;
  nodeModule.Module._load = function (request: string, ...rest: unknown[]) {
    if (request === './DdlSearchBar') {
      return { __esModule: true, default: DdlSearchBarStub };
    }
    if (request === '@/store/global') {
      return { __esModule: true, useGlobalStore: useGlobalStoreStub };
    }
    return originalLoad.call(this, request, ...rest);
  };

  const { act } = React;
  const { createRoot } = await import('react-dom/client');
  const { useDdlSearch } = await import('./useDdlSearch');
  const useGlobalStore = useGlobalStoreStub;

  let controller: HarnessController | null = null;
  const Harness = ({ sql, resetKey }: { sql: string; resetKey: string }) => {
    const search = useDdlSearch({ sql, resetKey });
    controller = search;
    return React.createElement(
      'div',
      { ...search.containerProps },
      React.createElement(
        'div',
        { 'data-sql-preview': 'canonical' },
        React.createElement('pre', { className: 'shiki' }, sql),
      ),
      search.renderAddons(),
    );
  };

  const mount = document.createElement('div');
  document.body.appendChild(mount);
  const root = createRoot(mount);
  const render = async (sql: string, resetKey = 'A') => {
    await act(async () => {
      root.render(React.createElement(Harness, { sql, resetKey }));
    });
  };
  await render(SQL_A);

  const container = mount.firstChild as HTMLElement;
  assert.equal(container.getAttribute('data-shortcut-scope'), 'viewDdl');
  assert.equal(container.tabIndex, 0);

  const pressKey = async (target: EventTarget, init: Record<string, unknown>) => {
    const event = new (globalThis as any).KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      ...init,
    });
    await act(async () => {
      target.dispatchEvent(event);
    });
    return event;
  };
  const flushRaf = () =>
    act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20));
    });

  const cmdF = { key: 'f', code: 'KeyF', metaKey: true };

  // Gate 3: without focus inside the scope the shortcut is inert.
  await pressKey(document.body, cmdF);
  assert.equal(controller!.searchOpen, false, 'shortcut outside the scope must not open search');

  // Gate 3: clicking DDL text moves focus into the scope container.
  await act(async () => {
    container.dispatchEvent(new (globalThis as any).MouseEvent('mousedown', { bubbles: true, cancelable: true }));
  });
  assert.equal(document.activeElement, container, 'mousedown on DDL text focuses the scope container');

  // Default binding opens search and focuses the input.
  await pressKey(container, cmdF);
  assert.equal(controller!.searchOpen, true, 'modifier+F inside the scope opens search');
  assert.ok(barStub.focusCalls.count >= 1, 'opening focuses the search input');
  assert.equal(container.getAttribute('data-ddl-search-open'), 'true');

  // Type a query: highlights register instance-scoped names over all matches.
  await act(async () => {
    barStub.lastProps.onChange('user_info');
  });
  await flushRaf();
  const names = Array.from(highlightRegistry.keys());
  assert.ok(names.some((n) => /^ddl-search-\d+$/.test(n)), 'all-match highlight registered');
  assert.ok(names.some((n) => /^ddl-search-active-\d+$/.test(n)), 'active-match highlight registered');
  const all = highlightRegistry.get(names.find((n) => /^ddl-search-\d+$/.test(n))!)!;
  assert.equal(all.ranges.length, 2, 'both occurrences of user_info highlighted');
  assert.ok(all.ranges.every((r) => r.toString() === 'user_info'));

  // SQL update re-maps offsets (async re-render path).
  await render(SQL_B);
  await act(async () => {
    barStub.lastProps.onChange('v_account');
  });
  await flushRaf();
  const allAfter = highlightRegistry.get(names.find((n) => /^ddl-search-\d+$/.test(n))!)!;
  assert.equal(allAfter.ranges.length, 1);
  assert.equal(allAfter.ranges[0].toString(), 'v_account');

  // Escape closes, clears the registry and returns focus to the container.
  await pressKey(container, { key: 'Escape', code: 'Escape' });
  assert.equal(controller!.searchOpen, false);
  assert.equal(highlightRegistry.size, 0, 'closing clears instance highlights');
  assert.equal(document.activeElement, container, 'closing returns focus to the scope container');

  // Gate 4: disabled config must not take over.
  await act(async () => useGlobalStore.setState({ shortcutOverrides: { ddlSearch: { binding: null } } }));
  await pressKey(container, cmdF);
  assert.equal(controller!.searchOpen, false, 'disabled shortcut must not open search');

  // Gate 4: rebinding is honoured.
  await act(async () => useGlobalStore.setState({ shortcutOverrides: { ddlSearch: { binding: 'Ctrl + G' } } }));
  await pressKey(container, cmdF);
  assert.equal(controller!.searchOpen, false, 'old binding no longer opens search after rebinding');
  await pressKey(container, { key: 'g', code: 'KeyG', ctrlKey: true });
  assert.equal(controller!.searchOpen, true, 'rebound binding opens search');
  await pressKey(container, { key: 'Escape', code: 'Escape' });
  assert.equal(controller!.searchOpen, false);

  // Gate 4: restoring defaults re-enables modifier+F.
  await act(async () => useGlobalStore.setState({ shortcutOverrides: {} }));
  await pressKey(container, cmdF);
  assert.equal(controller!.searchOpen, true, 'restored default binding opens search again');

  await act(async () => barStub.lastProps.onChange('v_account'));
  await flushRaf();
  const treeButton = document.createElement('button');
  document.body.appendChild(treeButton);
  treeButton.focus();
  await render(SQL_A, 'another-object');
  assert.equal(controller!.searchOpen, false, 'switching objects closes search');
  assert.equal(highlightRegistry.size, 0, 'switching objects clears highlights');
  assert.equal(document.activeElement, treeButton, 'object changes must not steal focus from the tree');
  await pressKey(container, cmdF);
  assert.equal(barStub.lastProps.value, '', 'reopening after an object switch starts with an empty query');

  await act(async () => {
    root.unmount();
  });
  assert.equal(highlightRegistry.size, 0, 'unmount clears instance highlights');
});
