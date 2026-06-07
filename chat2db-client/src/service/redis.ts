import createRequest from './base';
import { createSSEConnection } from '@/utils/sse';
import { formatParams } from '@/utils/url';

export interface IRedisKeyListParams {
  dataSourceId: number;
  databaseName?: string;
  searchKey?: string;
  cursor?: string;
  count?: number;
}

export interface IRedisKeyItem {
  name: string;
  value?: any;
  type?: string;
  ttl?: number;
  size?: number;
}

export interface IRedisKeyQueryParams {
  dataSourceId: number;
  databaseName?: string;
  keyName: string;
}

export interface IRedisKeyUpdateParams {
  dataSourceId: number;
  databaseName?: string;
  originalKey: string;
  updateKey: string;
  keyType: string;
  value: any;
  updateTtl?: number;
}

export interface IRedisKeyCreateParams {
  dataSourceId: number;
  databaseName?: string;
  name: string;
  keyType: string;
  value: any;
  ttl?: number;
}

export interface IRedisKeyDeleteParams {
  dataSourceId: number;
  databaseName?: string;
  keyName: string;
}

export interface IRedisMonitorStreamOptions {
  dataSourceId: number;
  databaseName?: string;
  uid: string;
  onCommand: (line: string) => void;
  onDone: () => void;
  onError: (message: string) => void;
}

export interface IRedisKeyStreamOptions extends IRedisKeyListParams {
  batchSize?: number;
  uid: string;
  onBatch: (items: IRedisKeyItem[], total: number, cursor: string, hasMore: boolean) => void;
  onDone: (total: number, cursor: string, hasMore: boolean) => void;
  onError: (message: string) => void;
}

const getKeyList = createRequest<IRedisKeyListParams, IRedisKeyItem[]>('/api/redis/key/list', {
  method: 'get',
  delayTime: 200,
});

const queryKey = createRequest<IRedisKeyQueryParams, IRedisKeyItem>('/api/redis/key/query', {
  method: 'get',
});

const updateKey = createRequest<IRedisKeyUpdateParams, void>('/api/redis/key/update', {
  method: 'post',
});

const createKey = createRequest<IRedisKeyCreateParams, void>('/api/redis/key/create', {
  method: 'post',
});

const deleteKey = createRequest<IRedisKeyDeleteParams, void>('/api/redis/key/delete', {
  method: 'post',
});

const streamMonitor = (options: IRedisMonitorStreamOptions) => {
  const { uid, onCommand, onDone, onError, ...params } = options;
  const url = `/api/redis/monitor/stream?${formatParams(params)}`;
  const eventSource = createSSEConnection({ url, uid });

  eventSource.addEventListener('command', (event: any) => {
    try {
      const data = event.data ? JSON.parse(event.data) : {};
      onCommand(data.line || '');
    } catch {
      onError('Redis monitor 数据解析失败');
      eventSource.close();
    }
  });

  eventSource.addEventListener('done', () => {
    onDone();
    eventSource.close();
  });

  eventSource.addEventListener('redis_error', (event: any) => {
    try {
      const data = event.data ? JSON.parse(event.data) : {};
      onError(data.message || 'Redis monitor 连接失败');
    } catch {
      onError('Redis monitor 连接失败');
    } finally {
      eventSource.close();
    }
  });

  eventSource.addEventListener('error', () => {
    onError('Redis monitor 连接已断开');
    eventSource.close();
  });

  return () => {
    eventSource.close();
  };
};

const streamKeyList = (options: IRedisKeyStreamOptions) => {
  const { uid, onBatch, onDone, onError, ...params } = options;
  const url = `/api/redis/key/stream?${formatParams(params)}`;
  const eventSource = createSSEConnection({ url, uid });

  eventSource.addEventListener('keys', (event: any) => {
    try {
      const data = JSON.parse(event.data);
      onBatch(data.items || [], data.total || 0, data.cursor || '0', Boolean(data.hasMore));
    } catch {
      onError('Redis key 数据解析失败');
      eventSource.close();
    }
  });

  eventSource.addEventListener('done', (event: any) => {
    try {
      const data = JSON.parse(event.data);
      onDone(data.total || 0, data.cursor || '0', Boolean(data.hasMore));
    } catch {
      onDone(0, '0', false);
    } finally {
      eventSource.close();
    }
  });

  eventSource.addEventListener('redis_error', (event: any) => {
    try {
      const data = event.data ? JSON.parse(event.data) : {};
      onError(data.message || 'Redis key 加载失败');
    } catch {
      onError('Redis key 加载失败');
    } finally {
      eventSource.close();
    }
  });

  eventSource.addEventListener('error', () => {
    onError('Redis key 加载连接失败');
    eventSource.close();
  });

  return () => {
    eventSource.close();
  };
};

export default {
  createKey,
  deleteKey,
  getKeyList,
  queryKey,
  streamKeyList,
  streamMonitor,
  updateKey,
};
