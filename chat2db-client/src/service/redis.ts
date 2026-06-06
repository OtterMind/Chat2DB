import createRequest from './base';

export interface IRedisKeyListParams {
  dataSourceId: number;
  databaseName?: string;
  searchKey?: string;
  count?: number;
}

export interface IRedisKeyItem {
  name: string;
  value?: string;
  type?: string;
  ttl?: number;
  size?: number;
}

const getKeyList = createRequest<IRedisKeyListParams, IRedisKeyItem[]>('/api/redis/key/list', {
  method: 'get',
  delayTime: 200,
});

export default {
  getKeyList,
};
