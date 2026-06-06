import React, { memo, useEffect, useMemo, useState } from 'react';
import { Button, Input, Table, Tag, Tooltip, message } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';

import redisService, { IRedisKeyItem } from '@/service/redis';

import styles from './index.less';

interface IProps {
  uniqueData: {
    dataSourceId: number;
    databaseName?: string;
  };
}

interface IRedisKeyRow extends IRedisKeyItem {
  rowKey: string;
  displayName: string;
  isGroup?: boolean;
  count?: number;
  children?: IRedisKeyRow[];
}

const DEFAULT_LIMIT = 5000;

const RedisDataView = memo((props: IProps) => {
  const { uniqueData } = props;
  const [searchKey, setSearchKey] = useState('');
  const [data, setData] = useState<IRedisKeyItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [lastRefreshTime, setLastRefreshTime] = useState<string>('');

  const loadData = async (keyword = searchKey) => {
    if (!uniqueData?.dataSourceId) {
      return;
    }
    setLoading(true);
    try {
      const list = await redisService.getKeyList({
        dataSourceId: uniqueData.dataSourceId,
        databaseName: uniqueData.databaseName,
        searchKey: keyword,
        count: DEFAULT_LIMIT,
      });
      setData(list || []);
      setLastRefreshTime(new Date().toLocaleTimeString());
    } catch (error) {
      message.error('Redis key 加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData('');
  }, [uniqueData?.dataSourceId, uniqueData?.databaseName]);

  const tableData = useMemo(() => {
    return buildRows(data);
  }, [data]);

  const columns: ColumnsType<IRedisKeyRow> = [
    {
      title: '键',
      dataIndex: 'displayName',
      width: '34%',
      render: (text, record) => {
        if (record.isGroup) {
          return (
            <span className={styles.groupName}>
              {text} <span>({record.count})</span>
            </span>
          );
        }
        return (
          <Tooltip title={record.name}>
            <div className={styles.keyName}>{text}</div>
          </Tooltip>
        );
      },
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 120,
      render: (type, record) => {
        if (record.isGroup || !type) {
          return null;
        }
        return <Tag color={getTypeColor(type)}>{type}</Tag>;
      },
    },
    {
      title: '值',
      dataIndex: 'value',
      render: (value, record) => {
        if (record.isGroup) {
          return null;
        }
        return (
          <Tooltip title={String(value || '')}>
            <div className={styles.valueCell}>{String(value || '')}</div>
          </Tooltip>
        );
      },
    },
    {
      title: '大小',
      dataIndex: 'size',
      width: 120,
      render: (size, record) => (record.isGroup ? null : formatSize(size)),
    },
    {
      title: 'TTL',
      dataIndex: 'ttl',
      width: 120,
      render: (ttl, record) => (record.isGroup ? null : formatTtl(ttl)),
    },
  ];

  return (
    <div className={styles.redisDataView}>
      <div className={styles.toolbar}>
        <Input.Search
          className={styles.searchInput}
          allowClear
          prefix={<SearchOutlined />}
          placeholder="键包含"
          value={searchKey}
          onChange={(event) => setSearchKey(event.target.value)}
          onSearch={(value) => loadData(value)}
        />
        <Button icon={<ReloadOutlined />} onClick={() => loadData()} />
        <div className={styles.meta}>
          {data.length} keys{lastRefreshTime ? ` · 上次刷新 ${lastRefreshTime}` : ''}
        </div>
      </div>
      <div className={styles.tableWrap}>
        <Table
          bordered={false}
          columns={columns}
          dataSource={tableData}
          loading={loading}
          pagination={false}
          rowKey="rowKey"
          size="small"
          scroll={{ y: 'calc(100vh - 170px)' }}
        />
      </div>
    </div>
  );
});

function buildRows(keys: IRedisKeyItem[]) {
  const root: IRedisKeyRow[] = [];
  const groupMap = new Map<string, IRedisKeyRow>();

  keys.forEach((item) => {
    const parts = item.name.split(':');
    if (parts.length === 1) {
      root.push(toLeaf(item, item.name));
      return;
    }

    let children = root;
    let prefix = '';
    for (let i = 0; i < parts.length - 1; i++) {
      prefix = prefix ? `${prefix}:${parts[i]}` : parts[i];
      let group = groupMap.get(prefix);
      if (!group) {
        group = {
          rowKey: `group-${prefix}`,
          name: prefix,
          displayName: parts[i],
          isGroup: true,
          count: 0,
          children: [],
        };
        groupMap.set(prefix, group);
        children.push(group);
      }
      group.count = (group.count || 0) + 1;
      children = group.children!;
    }
    children.push(toLeaf(item, parts[parts.length - 1]));
  });

  return root;
}

function toLeaf(item: IRedisKeyItem, displayName: string): IRedisKeyRow {
  return {
    ...item,
    rowKey: `key-${item.name}`,
    displayName,
  };
}

function getTypeColor(type: string) {
  const colors: Record<string, string> = {
    string: 'blue',
    hash: 'green',
    list: 'purple',
    set: 'orange',
    zset: 'cyan',
    stream: 'geekblue',
  };
  return colors[type] || 'default';
}

function formatTtl(ttl?: number) {
  if (ttl === undefined || ttl === null || ttl === -1) {
    return '(无 TTL)';
  }
  if (ttl === -2) {
    return '已过期';
  }
  return `${ttl}s`;
}

function formatSize(size?: number) {
  if (!size) {
    return '-';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export default RedisDataView;
