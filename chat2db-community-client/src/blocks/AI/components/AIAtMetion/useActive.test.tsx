import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import type { SuggestionItem, SuggestionSelectionIntent } from './interface';

const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>');
Object.defineProperties(globalThis, {
  window: { configurable: true, value: dom.window },
  document: { configurable: true, value: dom.window.document },
  navigator: { configurable: true, value: dom.window.navigator },
  IS_REACT_ACT_ENVIRONMENT: { configurable: true, value: true },
});

async function main() {
  const [{ createElement, act }, { createRoot }, { default: useActive }] = await Promise.all([
    import('react'), import('react-dom/client'), import('./useActive'),
  ]);
  const selections: Array<{ value: string; intent: SuggestionSelectionIntent }> = [];
  let cancellations = 0;
  function Menu({ names, open = true }: { names: string[]; open?: boolean }) {
    const items: SuggestionItem[] = names.map((name) => ({ kind: 'command', label: `/${name}`, value: name }));
    const [activePath, onKeyDown] = useActive(items, open,
      (path, intent) => selections.push({ value: path[0], intent }), () => cancellations++);
    return createElement('textarea', { onKeyDown, 'data-active': activePath[0] });
  }
  const root = createRoot(document.getElementById('root')!);
  const render = async (names: string[], open = true) => {
    await act(async () => root.render(createElement(Menu, { names, open })));
  };
  const press = async (key: string, options: KeyboardEventInit = {}) => {
    const event = new dom.window.KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...options });
    await act(async () => document.querySelector('textarea')!.dispatchEvent(event));
    return event.defaultPrevented;
  };

  await render(['new', 'model', 'help']);
  assert.equal(await press('Enter'), true);
  assert.deepEqual(selections.pop(), { value: 'new', intent: 'execute' });
  await press('ArrowDown');
  await press('Tab');
  assert.deepEqual(selections.pop(), { value: 'model', intent: 'complete' });

  // Filtering invalidates the previous active row before the next key event.
  await render(['help']);
  await press('Enter');
  assert.deepEqual(selections.pop(), { value: 'help', intent: 'execute' });
  assert.equal(await press('Enter', { shiftKey: true }), false);
  assert.equal(await press('Enter', { isComposing: true }), false);
  assert.equal(selections.length, 0);
  assert.equal(await press('Escape'), true);
  assert.equal(cancellations, 1);
  assert.equal(selections.length, 0);

  await render([]);
  assert.equal(await press('Enter'), false, 'An empty menu must not swallow input submission');
  await render(['help'], false);
  assert.equal(await press('Enter'), false);
  assert.equal(selections.length, 0);
  await act(async () => root.unmount());
  dom.window.close();
  console.log('Mounted suggestion keyboard tests passed: execute, completion, filtering, IME, cancellation and empty menus');
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
