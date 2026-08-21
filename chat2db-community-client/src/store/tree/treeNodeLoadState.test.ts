import assert from 'node:assert/strict';
import { shouldReuseTreeNodeChildren } from './treeNodeLoadState';

assert.equal(
  shouldReuseTreeNodeChildren({
    children: [],
    isDataSourceRoot: true,
    runtimeAvailability: 'unavailable',
  }),
  false,
  'an empty array left by a failed root load must not restore availability',
);
assert.equal(
  shouldReuseTreeNodeChildren({
    children: [],
    isDataSourceRoot: true,
    runtimeAvailability: 'available',
  }),
  true,
  'a successfully loaded empty data source may reuse its cached children',
);
assert.equal(
  shouldReuseTreeNodeChildren({
    children: [{}],
    isDataSourceRoot: true,
    runtimeAvailability: 'unavailable',
  }),
  false,
  'an unavailable data source must retry even when stale children remain',
);
assert.equal(
  shouldReuseTreeNodeChildren({
    children: [],
    isDataSourceRoot: false,
  }),
  true,
);
assert.equal(
  shouldReuseTreeNodeChildren({
    children: [],
    refresh: true,
    isDataSourceRoot: true,
    runtimeAvailability: 'available',
  }),
  false,
);
assert.equal(
  shouldReuseTreeNodeChildren({
    children: undefined,
    isDataSourceRoot: true,
  }),
  false,
);

console.log('Tree node load state tests passed');
