import { RedisFieldType } from '@/constants/redis';
import type { RedisDataItem } from '@/typings/redis';
import type { RedisRowIdentity } from './redisRowIdentity';

export type RedisDetailLoadStatus = 'idle' | 'loading' | 'loaded' | 'failed';

export function hasRedisDetailPayload(redisDataItem: RedisDataItem) {
  switch (redisDataItem.type) {
    case RedisFieldType.STRING:
      return redisDataItem.value !== null && redisDataItem.value !== undefined;
    case RedisFieldType.LIST:
      return redisDataItem.listValues !== null && redisDataItem.listValues !== undefined;
    case RedisFieldType.SET:
      return redisDataItem.values !== null && redisDataItem.values !== undefined;
    case RedisFieldType.ZSET:
      return redisDataItem.zsValues !== null && redisDataItem.zsValues !== undefined;
    case RedisFieldType.HASH:
      return redisDataItem.hashValues !== null && redisDataItem.hashValues !== undefined;
    case RedisFieldType.STREAM:
      return redisDataItem.streamValues !== null && redisDataItem.streamValues !== undefined;
    default:
      return false;
  }
}

export function isRedisDataItemLoaded(redisDataItem: RedisDataItem) {
  if (redisDataItem.isDraftFE) {
    return true;
  }
  if (redisDataItem.detailLoaded === false) {
    return false;
  }
  return hasRedisDetailPayload(redisDataItem);
}

export function shouldRetryRedisDetail(
  currentIdentity: RedisRowIdentity | null,
  nextIdentity: RedisRowIdentity | null,
  status: RedisDetailLoadStatus,
) {
  return Boolean(nextIdentity && currentIdentity === nextIdentity && status === 'failed');
}
