import assert from 'node:assert/strict';
import { isResultResourceActive } from './resourceActivity';

assert.equal(isResultResourceActive(undefined, 'result-1', 'result-1'), true);
assert.equal(isResultResourceActive(true, 'result-1', 'result-1'), true);
assert.equal(isResultResourceActive(true, 'result-1', 'result-2'), false);
assert.equal(isResultResourceActive(false, 'result-1', 'result-1'), false);
assert.equal(isResultResourceActive(true, undefined, 'result-1'), false);

console.log('SearchResult resource activity tests passed');
