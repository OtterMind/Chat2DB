import assert from 'node:assert/strict';
import { shouldProcessTabScrollRequest } from './activeTabScroll';

assert.equal(shouldProcessTabScrollRequest(undefined, 'table-1', undefined), true);
assert.equal(
  shouldProcessTabScrollRequest({ activeKey: 'table-1', scrollKey: 1 }, 'table-1', 2),
  true,
  'a new explicit request locates an already active tab',
);
assert.equal(
  shouldProcessTabScrollRequest({ activeKey: 'table-1', scrollKey: 1 }, 'table-1', undefined),
  false,
  'removing a request from an inactive split pane must not trigger another scroll',
);
assert.equal(
  shouldProcessTabScrollRequest({ activeKey: 'table-1', scrollKey: 2 }, 'table-1', 2),
  false,
  'an already processed request is ignored',
);

console.log('Active tab scroll decision tests passed');
