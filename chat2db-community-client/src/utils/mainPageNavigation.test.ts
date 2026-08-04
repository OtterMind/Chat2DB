import assert from 'node:assert/strict';
import { DEFAULT_MAIN_PAGE_ACTIVE_TAB, resolveInitialMainPage } from './mainPageNavigation';

assert.equal(DEFAULT_MAIN_PAGE_ACTIVE_TAB, 'workspace', 'a new user should start on the workspace entry');
assert.equal(
  resolveInitialMainPage('', 'stream'),
  'stream',
  'the previously selected entry should be restored when the URL has no route',
);
assert.equal(
  resolveInitialMainPage('dashboard', 'stream'),
  'dashboard',
  'an explicit URL route should take precedence over the persisted entry',
);
assert.equal(
  resolveInitialMainPage('', ''),
  'workspace',
  'workspace should be used when neither a route nor a persisted entry exists',
);

console.log('Main page navigation tests passed.');
