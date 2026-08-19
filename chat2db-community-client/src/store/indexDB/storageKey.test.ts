import assert from 'node:assert/strict';
import { resolveIndexedDbStorageKey } from './storageKey';

assert.equal(resolveIndexedDbStorageKey('workspaceTabId', ''), 'workspaceTabId');
assert.equal(resolveIndexedDbStorageKey('consoleId', ''), 'consoleId');
assert.equal(resolveIndexedDbStorageKey('workspaceTabId', 'chat2db'), 'chat2db:workspaceTabId');
assert.equal(resolveIndexedDbStorageKey('workspaceTabId', 'chat2db_community'), 'chat2db_community:workspaceTabId');

console.log('IndexedDB storage key tests passed');
