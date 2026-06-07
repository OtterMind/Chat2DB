import React, { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Empty, Input, InputNumber, Modal, Select, Table, Tag, Tooltip, message } from 'antd';
import {
  CloseOutlined,
  DeleteOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  SearchOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { v4 as uuid } from 'uuid';

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
const NO_TTL = -1;
const KEY_TYPES = ['string', 'hash', 'list', 'set', 'zset'];

interface IHashFieldRow {
  id: string;
  field: string;
  value: string;
}

const RedisDataView = memo((props: IProps) => {
  const { uniqueData } = props;
  const [searchKey, setSearchKey] = useState('');
  const [data, setData] = useState<IRedisKeyItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [selectedKey, setSelectedKey] = useState('');
  const [detail, setDetail] = useState<IRedisKeyItem | null>(null);
  const [editorKey, setEditorKey] = useState('');
  const [editorType, setEditorType] = useState('string');
  const [editorTtl, setEditorTtl] = useState<number | null>(NO_TTL);
  const [textValue, setTextValue] = useState('');
  const [hashFields, setHashFields] = useState<IHashFieldRow[]>([]);
  const [hashFieldFilter, setHashFieldFilter] = useState('');
  const [lastRefreshTime, setLastRefreshTime] = useState<string>('');
  const [keyLimit, setKeyLimit] = useState(DEFAULT_LIMIT);
  const [scanCursor, setScanCursor] = useState('0');
  const [hasMore, setHasMore] = useState(false);
  const [creating, setCreating] = useState(false);
  const closeStreamRef = useRef<(() => void) | null>(null);
  const streamIdRef = useRef('');
  const detailRequestRef = useRef('');
  const tableWrapRef = useRef<HTMLDivElement>(null);
  const [tableScrollY, setTableScrollY] = useState(240);

  const hydrateEditor = (keyDetail: IRedisKeyItem) => {
    const type = normalizeType(keyDetail.type);
    const value = keyDetail.value;
    const ttl = keyDetail.ttl === undefined || keyDetail.ttl === null ? NO_TTL : keyDetail.ttl;
    setEditorKey(keyDetail.name);
    setEditorType(type || 'string');
    setEditorTtl(ttl);
    setHashFieldFilter('');
    if (type === 'hash' && value && typeof value === 'object' && !Array.isArray(value)) {
      setHashFields(
        Object.entries(value).map(([field, fieldValue]) => ({
          id: uuid(),
          field,
          value: String(fieldValue ?? ''),
        })),
      );
      setTextValue('');
      return;
    }
    setHashFields([]);
    if (Array.isArray(value)) {
      setTextValue(value.map((item) => String(item ?? '')).join('\n'));
      return;
    }
    setTextValue(value === undefined || value === null ? '' : String(value));
  };

  const loadData = (keyword = searchKey, count = keyLimit, cursor = '0', append = false) => {
    if (!uniqueData?.dataSourceId) {
      return;
    }
    closeStreamRef.current?.();
    const streamId = uuid();
    streamIdRef.current = streamId;
    if (!append) {
      setData([]);
      setScanCursor('0');
      setHasMore(false);
    }
    setLoading(true);
    closeStreamRef.current = redisService.streamKeyList({
      uid: streamId,
      dataSourceId: uniqueData.dataSourceId,
      databaseName: uniqueData.databaseName,
      searchKey: keyword,
      cursor,
      count,
      onBatch: (items) => {
        if (streamIdRef.current !== streamId) {
          return;
        }
        setData((current) => current.concat(items || []));
      },
      onDone: (_, nextCursor, nextHasMore) => {
        if (streamIdRef.current !== streamId) {
          return;
        }
        setScanCursor(nextCursor);
        setHasMore(nextHasMore);
        setLoading(false);
        setLastRefreshTime(new Date().toLocaleTimeString());
        closeStreamRef.current = null;
      },
      onError: (errorMessage) => {
        if (streamIdRef.current !== streamId) {
          return;
        }
        message.error(errorMessage || 'Redis key 加载失败');
        setLoading(false);
        closeStreamRef.current = null;
      },
    });
  };

  const loadKeyDetail = (keyName: string) => {
    if (!uniqueData?.dataSourceId || !keyName) {
      return;
    }
    const requestId = uuid();
    detailRequestRef.current = requestId;
    setSelectedKey(keyName);
    setCreating(false);
    setDetail(null);
    setDetailLoading(true);
    redisService
      .queryKey({
        dataSourceId: uniqueData.dataSourceId,
        databaseName: uniqueData.databaseName,
        keyName,
      })
      .then((keyDetail) => {
        if (detailRequestRef.current !== requestId) {
          return;
        }
        setDetail(keyDetail);
        hydrateEditor(keyDetail);
      })
      .catch((error) => {
        message.error(String(error || 'Redis key 详情加载失败'));
      })
      .finally(() => {
        if (detailRequestRef.current === requestId) {
          setDetailLoading(false);
        }
      });
  };

  const saveKey = () => {
    if (!uniqueData?.dataSourceId || (!detail && !creating)) {
      return;
    }
    const nextKey = editorKey.trim();
    if (!nextKey) {
      message.warning('键名不能为空');
      return;
    }
    const type = normalizeType(creating ? editorType : detail?.type);
    setSaving(true);
    const value = buildEditorValue(type, textValue, hashFields);
    const saveRequest = creating
      ? redisService.createKey({
          dataSourceId: uniqueData.dataSourceId,
          databaseName: uniqueData.databaseName,
          name: nextKey,
          keyType: type,
          value,
          ttl: editorTtl ?? NO_TTL,
        })
      : redisService.updateKey({
          dataSourceId: uniqueData.dataSourceId,
          databaseName: uniqueData.databaseName,
          originalKey: detail!.name,
          updateKey: nextKey,
          keyType: type,
          value,
          updateTtl: editorTtl ?? NO_TTL,
        });
    saveRequest
      .then(() => {
        message.success(creating ? 'Redis key 已新增' : 'Redis key 已保存');
        setCreating(false);
        setSelectedKey(nextKey);
        loadKeyDetail(nextKey);
        loadData(searchKey, keyLimit);
      })
      .catch((error) => {
        message.error(String(error || 'Redis key 保存失败'));
      })
      .finally(() => setSaving(false));
  };

  const addHashField = () => {
    setHashFields((current) => current.concat({ id: uuid(), field: '', value: '' }));
  };

  const updateHashField = (id: string, patch: Partial<IHashFieldRow>) => {
    setHashFields((current) => current.map((item) => (item.id === id ? { ...item, ...patch } : item)));
  };

  const removeHashField = (id: string) => {
    setHashFields((current) => current.filter((item) => item.id !== id));
  };

  const openCreateKey = () => {
    detailRequestRef.current = '';
    setCreating(true);
    setSelectedKey('');
    setDetail({ name: '', type: 'string', value: '', ttl: NO_TTL });
    setEditorKey('');
    setEditorType('string');
    setEditorTtl(NO_TTL);
    setTextValue('');
    setHashFields([{ id: uuid(), field: '', value: '' }]);
    setHashFieldFilter('');
  };

  const deleteSelectedKey = () => {
    const keyName = detail?.name || selectedKey;
    if (!uniqueData?.dataSourceId || !keyName || creating) {
      message.warning('请选择要删除的 key');
      return;
    }
    Modal.confirm({
      title: '删除 Redis key',
      content: keyName,
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: () =>
        redisService
          .deleteKey({
            dataSourceId: uniqueData.dataSourceId,
            databaseName: uniqueData.databaseName,
            keyName,
          })
          .then(() => {
            message.success('Redis key 已删除');
            setSelectedKey('');
            setDetail(null);
            loadData(searchKey, keyLimit);
          })
          .catch((error) => {
            message.error(String(error || 'Redis key 删除失败'));
          }),
    });
  };

  const refreshData = () => {
    loadData(searchKey, keyLimit);
  };

  const getMoreData = () => {
    if (!hasMore) {
      message.info('没有更多 Redis key');
      return;
    }
    setKeyLimit(DEFAULT_LIMIT);
    loadData(searchKey, DEFAULT_LIMIT, scanCursor, true);
  };

  const getAllData = () => {
    setKeyLimit(-1);
    loadData(searchKey, -1);
  };

  useEffect(() => {
    loadData('');
    return () => {
      closeStreamRef.current?.();
      closeStreamRef.current = null;
    };
  }, [uniqueData?.dataSourceId, uniqueData?.databaseName]);

  useEffect(() => {
    const tableWrap = tableWrapRef.current;
    if (!tableWrap) {
      return;
    }

    const updateTableScrollY = () => {
      const headerHeight = tableWrap.querySelector('.ant-table-thead')?.getBoundingClientRect().height || 39;
      const wrapHeight = tableWrap.getBoundingClientRect().height;
      setTableScrollY(Math.max(120, Math.floor(wrapHeight - headerHeight)));
    };

    updateTableScrollY();

    if (!window.ResizeObserver) {
      window.addEventListener('resize', updateTableScrollY);
      return () => window.removeEventListener('resize', updateTableScrollY);
    }

    const resizeObserver = new ResizeObserver(updateTableScrollY);
    resizeObserver.observe(tableWrap);
    return () => resizeObserver.disconnect();
  }, []);

  const tableData = useMemo(() => {
    return buildRows(data);
  }, [data]);

  const filteredHashFields = useMemo(() => {
    const keyword = hashFieldFilter.trim().toLowerCase();
    if (!keyword) {
      return hashFields;
    }
    return hashFields.filter(
      (item) => item.field.toLowerCase().includes(keyword) || item.value.toLowerCase().includes(keyword),
    );
  }, [hashFieldFilter, hashFields]);

  const hashColumns: ColumnsType<IHashFieldRow> = [
    {
      title: '字段',
      dataIndex: 'field',
      width: 220,
      render: (value, record) => (
        <Input value={value} onChange={(event) => updateHashField(record.id, { field: event.target.value })} />
      ),
    },
    {
      title: '值',
      dataIndex: 'value',
      render: (value, record) => (
        <Input value={value} onChange={(event) => updateHashField(record.id, { value: event.target.value })} />
      ),
    },
    {
      title: '',
      dataIndex: 'action',
      width: 44,
      render: (_, record) => (
        <Button icon={<DeleteOutlined />} size="small" type="text" onClick={() => removeHashField(record.id)} />
      ),
    },
  ];

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
          onSearch={(value) => {
            setKeyLimit(DEFAULT_LIMIT);
            loadData(value, DEFAULT_LIMIT);
          }}
        />
        <div className={styles.meta}>
          {data.length} keys{lastRefreshTime ? ` · 上次刷新 ${lastRefreshTime}` : ''}
        </div>
      </div>
      <div className={styles.tableWrap} ref={tableWrapRef}>
        <Table
          bordered={false}
          columns={columns}
          dataSource={tableData}
          loading={loading}
          pagination={false}
          rowClassName={(record) => (record.name === selectedKey && !record.isGroup ? styles.selectedRow : '')}
          rowKey="rowKey"
          size="small"
          scroll={{ y: tableScrollY }}
          onRow={(record) => ({
            onClick: () => {
              if (!record.isGroup) {
                loadKeyDetail(record.name);
              }
            },
          })}
        />
      </div>
      <div className={styles.bottomBar}>
        <div className={styles.bottomActions}>
          <Tooltip title="添加 key">
            <Button size="small" type="text" onClick={openCreateKey}>
              +
            </Button>
          </Tooltip>
          <Tooltip title="删除 key">
            <Button disabled={!selectedKey || creating} size="small" type="text" onClick={deleteSelectedKey}>
              -
            </Button>
          </Tooltip>
        </div>
        <div className={styles.bottomMeta}>
          <span>上次刷新时间: {lastRefreshTime || '-'}</span>
          <Button icon={<ReloadOutlined />} loading={loading} size="small" type="text" onClick={refreshData}>
            刷新
          </Button>
          <Button disabled={!hasMore} loading={loading} size="small" type="text" onClick={getMoreData}>
            获取更多
          </Button>
          <Button loading={loading} size="small" type="text" onClick={getAllData}>
            获取全部
          </Button>
          <Button icon={<SettingOutlined />} size="small" type="text" />
        </div>
      </div>
      <div className={styles.editorPane}>
        {detail || detailLoading ? (
          <>
            <div className={styles.editorHeader}>
              <span>{creating ? '新增 Key' : '编辑器'}</span>
              <div className={styles.editorActions}>
                <Button
                  icon={<CloseOutlined />}
                  size="small"
                  onClick={() => {
                    setDetail(null);
                    setSelectedKey('');
                    setCreating(false);
                  }}
                />
                <Button
                  icon={<SaveOutlined />}
                  loading={saving}
                  size="small"
                  type="primary"
                  onClick={saveKey}
                  disabled={(!detail && !creating) || !isEditableType(creating ? editorType : detail?.type)}
                >
                  应用
                </Button>
              </div>
            </div>
            <div className={styles.editorBody}>
              <div className={styles.formRow}>
                <span className={styles.label}>键名:</span>
                <Input value={editorKey} onChange={(event) => setEditorKey(event.target.value)} />
              </div>
              <div className={styles.formRow}>
                <span className={styles.label}>键类型:</span>
                {creating ? (
                  <Select
                    className={styles.typeSelect}
                    value={editorType}
                    options={KEY_TYPES.map((type) => ({ label: type, value: type }))}
                    onChange={(value) => {
                      setEditorType(value);
                      setTextValue('');
                      setHashFields(value === 'hash' ? [{ id: uuid(), field: '', value: '' }] : []);
                      setHashFieldFilter('');
                    }}
                  />
                ) : (
                  <Tag color={getTypeColor(detail?.type || '')}>{detail?.type || '-'}</Tag>
                )}
              </div>
              <div className={styles.formRow}>
                <span className={styles.label}>值:</span>
                <div className={styles.valueEditor}>{renderValueEditor()}</div>
              </div>
              <div className={styles.formRow}>
                <span className={styles.label}>TTL:</span>
                <InputNumber
                  className={styles.ttlInput}
                  min={-1}
                  placeholder="无 TTL"
                  value={editorTtl}
                  onChange={(value) => setEditorTtl(value === null ? NO_TTL : Number(value))}
                />
                <span className={styles.ttlHint}>秒，-1 表示无 TTL</span>
              </div>
            </div>
          </>
        ) : (
          <Empty
            className={styles.emptyEditor}
            description="选择一个 Redis key 后编辑"
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          />
        )}
      </div>
    </div>
  );

  function renderValueEditor() {
    const type = normalizeType(creating ? editorType : detail?.type);
    if (detailLoading) {
      return <div className={styles.editorLoading}>加载中...</div>;
    }
    if (!isEditableType(type)) {
      return <div className={styles.readonlyTip}>暂不支持编辑 {type || 'unknown'} 类型</div>;
    }
    if (type === 'hash') {
      return (
        <>
          <div className={styles.hashFilterBar}>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="过滤字段或值"
              size="small"
              value={hashFieldFilter}
              onChange={(event) => setHashFieldFilter(event.target.value)}
            />
            <span>
              {filteredHashFields.length}/{hashFields.length} 个字段
            </span>
          </div>
          <Table columns={hashColumns} dataSource={filteredHashFields} pagination={false} rowKey="id" size="small" />
          <div className={styles.fieldToolbar}>
            <Button icon={<PlusOutlined />} size="small" onClick={addHashField}>
              字段
            </Button>
            <span>{hashFields.length} 个字段</span>
          </div>
        </>
      );
    }
    return (
      <Input.TextArea
        value={textValue}
        onChange={(event) => setTextValue(event.target.value)}
        rows={type === 'string' ? 5 : 7}
        placeholder={type === 'string' ? '字符串值' : '每行一个元素'}
      />
    );
  }
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

function normalizeType(type?: string) {
  return (type || '').toLowerCase();
}

function isEditableType(type?: string) {
  return ['string', 'hash', 'list', 'set', 'zset'].includes(normalizeType(type));
}

function buildEditorValue(type: string, textValue: string, hashFields: IHashFieldRow[]) {
  if (type === 'hash') {
    return hashFields.reduce<Record<string, string>>((result, item) => {
      const field = item.field.trim();
      if (field) {
        result[field] = item.value;
      }
      return result;
    }, {});
  }
  if (type === 'list' || type === 'set' || type === 'zset') {
    return textValue.split('\n').filter((item) => item.length > 0);
  }
  return textValue;
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
