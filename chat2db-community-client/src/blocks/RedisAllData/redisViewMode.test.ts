import assert from 'node:assert/strict';
import {
  createRedisKeyViewModeStorageKey,
  persistRedisKeyViewMode,
  readRedisKeyViewMode,
} from './redisViewMode';

const storageKey = 'redis-view-mode-test';
const values = new Map<string, string>();
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => {
    values.set(key, value);
  },
};

assert.notEqual(
  createRedisKeyViewModeStorageKey('community', 'desktop'),
  createRedisKeyViewModeStorageKey('enterprise', 'desktop'),
);
assert.equal(
  createRedisKeyViewModeStorageKey('community', 'community'),
  'chat2db.community.community.redis.key-view-mode.v1',
);
assert.equal(
  createRedisKeyViewModeStorageKey('enterprise', 'offline'),
  'chat2db.enterprise.offline.redis.key-view-mode.v1',
);

assert.equal(readRedisKeyViewMode(undefined, storageKey), 'list');
assert.equal(readRedisKeyViewMode(storage, storageKey), 'list');

values.set(storageKey, 'invalid');
assert.equal(readRedisKeyViewMode(storage, storageKey), 'list');

values.set(storageKey, 'tree');
assert.equal(readRedisKeyViewMode(storage, storageKey), 'tree');

persistRedisKeyViewMode(storage, storageKey, 'list');
assert.equal(values.get(storageKey), 'list');

const unavailableStorage = {
  getItem: () => {
    throw new Error('unavailable');
  },
  setItem: () => {
    throw new Error('unavailable');
  },
};
assert.equal(readRedisKeyViewMode(unavailableStorage, storageKey), 'list');
assert.doesNotThrow(() => persistRedisKeyViewMode(unavailableStorage, storageKey, 'tree'));

console.log('Redis view mode tests passed');
