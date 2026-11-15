export enum RedisFieldType {
  STRING = 'string',
  LIST = 'list',
  SET = 'set',
  ZSET = 'zset',
  HASH = 'hash',
  STREAM = 'stream',
  JSON = 'json',
}

export enum ActionType {
  ORIGINAL = 'original',
  ADD = 'add',
  UPDATE = 'update',
  DELETE = 'delete',
}

export const redisFieldTypeList = [
  { label: RedisFieldType.STRING, value: RedisFieldType.STRING },
  { label: RedisFieldType.LIST, value: RedisFieldType.LIST },
  { label: RedisFieldType.SET, value: RedisFieldType.SET },
  { label: RedisFieldType.ZSET, value: RedisFieldType.ZSET },
  { label: RedisFieldType.HASH, value: RedisFieldType.HASH },
  { label: RedisFieldType.STREAM, value: RedisFieldType.STREAM },
  { label: RedisFieldType.JSON, value: RedisFieldType.JSON },
];
