import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createElement, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { act } from 'react-dom/test-utils';
import LazyMount from './index';

const dom = new JSDOM('<div id="root"></div>');
Object.assign(globalThis, { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true });
const container = document.getElementById('root')!;
const root = createRoot(container);
let mountCount = 0;
let unmountCount = 0;

function Editor() {
  useEffect(() => {
    mountCount += 1;
    return () => { unmountCount += 1; };
  }, []);
  return createElement('textarea', { defaultValue: 'disk content' });
}

function render(active: boolean) {
  act(() => root.render(
    <LazyMount active={active}>
      <Editor />
    </LazyMount>,
  ));
}

render(false);
assert.equal(container.querySelector('textarea'), null, 'unvisited hidden content must not initialize');
assert.equal(mountCount, 0);
render(true);
const editor = container.querySelector('textarea')!;
assert.equal(mountCount, 1);
editor.value = 'unsaved local edits';
render(false);
assert.equal(container.querySelector('textarea'), editor, 'hiding a visited tab must keep its editor mounted');
render(true);
assert.equal(container.querySelector('textarea')?.value, 'unsaved local edits');
assert.equal(mountCount, 1, 'switching back must not initialize or reload the editor again');
act(() => root.unmount());
assert.equal(unmountCount, 1);
dom.window.close();
console.log('lazy mount lifecycle tests passed');
