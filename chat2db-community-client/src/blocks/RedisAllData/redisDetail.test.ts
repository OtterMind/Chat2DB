import assert from 'node:assert/strict';
import { RedisFieldType } from '@/constants/redis';
import type { RedisDataItem } from '@/typings/redis';
import { hasRedisDetailPayload, isRedisDataItemLoaded, shouldRetryRedisDetail } from './redisDetail';

function item(overrides: Partial<RedisDataItem>): RedisDataItem {
  return {
    name: 'key',
    type: RedisFieldType.STRING,
    ttl: -1,
    ...overrides,
  };
}

assert.equal(
  isRedisDataItemLoaded(item({ detailLoaded: false, value: null })),
  false,
  'a scan handle must remain unloaded even when nullable detail fields are serialized',
);
assert.equal(
  isRedisDataItemLoaded(item({ value: null })),
  false,
  'legacy scan responses with null detail fields must remain unloaded',
);
assert.equal(isRedisDataItemLoaded(item({ detailLoaded: true, value: '' })), true, 'empty strings are values');
assert.equal(
  isRedisDataItemLoaded(item({ type: RedisFieldType.LIST, detailLoaded: true, listValues: [] })),
  true,
  'empty arrays are loaded payloads',
);
assert.equal(
  hasRedisDetailPayload(item({ type: RedisFieldType.HASH, detailLoaded: true, hashValues: null })),
  false,
  'a loaded marker cannot make a malformed detail payload valid',
);
assert.equal(shouldRetryRedisDetail('redis-key:key', 'redis-key:key', 'failed'), true);
assert.equal(shouldRetryRedisDetail('redis-key:key', 'redis-key:key', 'loaded'), false);
assert.equal(shouldRetryRedisDetail('redis-key:key', 'redis-key:other', 'failed'), false);

console.log('Redis detail state tests passed');
