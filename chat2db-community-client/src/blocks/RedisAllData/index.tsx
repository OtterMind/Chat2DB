import { memo, useState, useRef, useMemo, useEffect, useReducer, useCallback } from 'react';
import { Button, Segmented, Tooltip } from 'antd';
import { List, ListTree, X } from 'lucide-react';
import i18n from '@/i18n';
import { useStyles } from './style';
import { RedisDataItem } from '@/typings/redis';
import { RedisFieldType } from '@/constants/redis';
import EditData from './EditData';
import BaseTable, { BaseTableRef } from '@/components/BaseTable';
import SearchBar from '@/components/SearchBar';
import redisServer from '@/service/nonRelationalDatabase/redis';
import SplitPane from 'react-split-pane';
import { ToolbarBtn } from '@chat2db/ui';
import openUnifiedDeletion from '@/utils/staticModal/unifiedDeletion';
import {
  buildRedisKeyTree,
  collectRedisBranchGroupKeys,
  collectRedisGroupKeys,
  getRedisTreeRowIndex,
  type RedisKeyTreeNode,
} from './redisKeyTree';
import {
  createRedisKeyViewModeStorageKey,
  getRedisViewModeStorage,
  persistRedisKeyViewMode,
  readRedisKeyViewMode,
  type RedisKeyViewMode,
} from './redisViewMode';
import { INITIAL_REDIS_EXPANSION_STATE, redisExpansionReducer } from './redisExpansion';
import {
  getRedisDataItemIdentity,
  REDIS_DRAFT_ROW_IDENTITY,
  resolveRedisDataItem,
  type RedisRowIdentity,
} from './redisRowIdentity';
import { RedisEditSessionRegistry, type RedisEditSessionToken } from './redisEditSession';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';

const REDIS_SCAN_COUNT = 1000;
const REDIS_KEY_VIEW_MODE_STORAGE_KEY = createRedisKeyViewModeStorageKey(
  runtimeEditionConfig.clientStorageEdition,
  __RUNTIME_ENV__,
);
const INITIAL_SCAN_CURSOR = '0';
const EDIT_PANE_COLLAPSED_SIZE = 0;
const EDIT_PANE_DEFAULT_SIZE = 320;
const EDIT_PANE_COLLAPSE_THRESHOLD = 50;
const REDIS_TABLE_COLUMN_SIZES = [420, 80, 100];
const SplitPaneAny = SplitPane as any;

function formatTime(time: number) {
  if (time < 60) {
    return `${Math.floor(time)} ${i18n('common.text.second')}`;
  } else if (time < 60 * 60) {
    return `${Math.floor(time / 60)} ${i18n('common.text.minute')}`;
  } else if (time < 24 * 60 * 60) {
    return `${Math.floor(time / 60 / 60)} ${i18n('common.text.hour')}`;
  } else {
    return `${Math.floor(time / 60 / 60 / 24)} ${i18n('common.text.day')}`;
  }
}

function formatRedisTtl(value?: number | null) {
  if (value === undefined || value === null || value < -1) {
    return '-';
  }
  if (value === -1) {
    return i18n('redis.noExpirationTime');
  }
  return formatTime(value);
}

function isRedisDataItemLoaded(redisDataItem: RedisDataItem) {
  if (redisDataItem.isDraftFE) {
    return true;
  }
  if (redisDataItem.type === RedisFieldType.STRING) {
    return redisDataItem.value !== undefined;
  }
  if (redisDataItem.type === RedisFieldType.STREAM) {
    return redisDataItem.streamValues !== undefined;
  }
  return (
    redisDataItem.values !== undefined ||
    redisDataItem.listValues !== undefined ||
    redisDataItem.hashValues !== undefined ||
    redisDataItem.zsValues !== undefined
  );
}

function mergeRedisKeys(current: RedisDataItem[], next: RedisDataItem[]) {
  const itemMap = new Map<string | null, RedisDataItem>();
  current.forEach((item) => {
    itemMap.set(item.name, item);
  });
  next.forEach((item) => {
    if (!itemMap.has(item.name)) {
      itemMap.set(item.name, item);
    }
  });
  return Array.from(itemMap.values());
}

interface IProps {
  className?: string;
  uniqueData: {
    dataSourceId: string;
    dataSourceName: string;
    databaseName: string;
  };
}

const RedisAllData = (props) => {
  const { className, uniqueData } = props;
  const { styles, cx } = useStyles();
  const [tableData, setTableData] = useState<RedisDataItem[] | null>(null);
  const [selectedRowIdentity, setSelectedRowIdentity] = useState<RedisRowIdentity | null>(null);
  const [curRedisDataItem, setRedisDataItem] = useState<RedisDataItem | null>(null);
  const [editPaneSize, setEditPaneSize] = useState(EDIT_PANE_COLLAPSED_SIZE);
  const [searchBarValue, setSearchBarValue] = useState('');
  const [presenceDraft, setPresenceDraft] = useState(false);
  const [scanCursor, setScanCursor] = useState(INITIAL_SCAN_CURSOR);
  const [scanComplete, setScanComplete] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [detailLoadingKey, setDetailLoadingKey] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<RedisKeyViewMode>(() =>
    readRedisKeyViewMode(
      getRedisViewModeStorage(),
      REDIS_KEY_VIEW_MODE_STORAGE_KEY,
    ),
  );
  const [expansionState, dispatchExpansion] = useReducer(
    redisExpansionReducer,
    INITIAL_REDIS_EXPANSION_STATE,
  );
  const [appliedSearchKey, setAppliedSearchKey] = useState('');
  const baseTableRef = useRef<BaseTableRef>(null);
  const scanRequestIdRef = useRef(0);
  const detailRequestIdRef = useRef(0);
  const selectedRowIdentityRef = useRef<RedisRowIdentity | null>(null);
  const deletedIdentityScanRequestRef = useRef<Map<RedisRowIdentity, number>>(new Map());
  const [editSessionRegistry] = useState(() => new RedisEditSessionRegistry());
  const activeEditSessionRef = useRef<RedisEditSessionToken | null>(null);
  const updateSelectedRowIdentity = useCallback((nextIdentity: RedisRowIdentity | null) => {
    if (selectedRowIdentityRef.current === nextIdentity) {
      return;
    }
    selectedRowIdentityRef.current = nextIdentity;
    detailRequestIdRef.current += 1;
    activeEditSessionRef.current = nextIdentity ? editSessionRegistry.begin(nextIdentity) : null;
    setSelectedRowIdentity(nextIdentity);
    setRedisDataItem(null);
    setDetailLoadingKey(null);
  }, [editSessionRegistry]);
  const selectedRedisRow = useMemo(
    () => resolveRedisDataItem(tableData, selectedRowIdentity),
    [tableData, selectedRowIdentity],
  );
  const selectedRedisDataItem = selectedRedisRow?.item;
  const selectedRows = useMemo(
    () => (selectedRedisRow ? [selectedRedisRow.index] : []),
    [selectedRedisRow],
  );
  const hasEditTarget = Boolean(selectedRedisDataItem);
  const redisKeyTreeData = useMemo(() => buildRedisKeyTree(tableData || []), [tableData]);
  const redisGroupKeys = useMemo(() => collectRedisGroupKeys(redisKeyTreeData), [redisKeyTreeData]);
  const redisBranchGroupKeys = useMemo(
    () => collectRedisBranchGroupKeys(redisKeyTreeData),
    [redisKeyTreeData],
  );
  const automaticallyExpandedGroupKeys = appliedSearchKey.trim() ? redisGroupKeys : redisBranchGroupKeys;
  const redisTreeMode = useMemo(
    () => ({
      openKeys: expansionState.expandedKeys,
      onChangeOpenKeys: (nextKeys: string[]) =>
        dispatchExpansion({ type: 'userChange', expandedKeys: nextKeys }),
      isLeafNode: (node: RedisKeyTreeNode) => node.kind === 'key',
      clickArea: 'cell' as const,
      iconIndent: 0,
      iconGap: 2,
      indentSize: 18,
      stopClickEventPropagation: true,
    }),
    [expansionState.expandedKeys],
  );
  const handleSelectedRowsChange = useCallback(
    (nextSelectedRows: number[]) => {
      const selectedItem = tableData?.[nextSelectedRows[0]];
      updateSelectedRowIdentity(selectedItem ? getRedisDataItemIdentity(selectedItem) || null : null);
    },
    [tableData, updateSelectedRowIdentity],
  );
  const handleActivateRedisRow = useCallback(
    (rowIndex: number) => handleSelectedRowsChange([rowIndex]),
    [handleSelectedRowsChange],
  );

  useEffect(() => {
    getTableData();
  }, []);

  useEffect(() => {
    if (selectedRedisDataItem) {
      const selectedIdentity = getRedisDataItemIdentity(selectedRedisDataItem);
      if (!selectedIdentity || selectedRowIdentityRef.current !== selectedIdentity) {
        return;
      }
      if (isRedisDataItemLoaded(selectedRedisDataItem)) {
        setDetailLoadingKey(null);
        setRedisDataItem(selectedRedisDataItem);
        return;
      }
      getRedisDataItemDetail(selectedRedisDataItem);
    } else {
      if (selectedRowIdentityRef.current !== selectedRowIdentity) {
        return;
      }
      detailRequestIdRef.current += 1;
      setDetailLoadingKey(null);
      setRedisDataItem(null);
    }
  }, [selectedRedisDataItem, selectedRowIdentity]);

  useEffect(() => {
    if (hasEditTarget) {
      setEditPaneSize((currentSize) =>
        currentSize === EDIT_PANE_COLLAPSED_SIZE ? EDIT_PANE_DEFAULT_SIZE : currentSize,
      );
      return;
    }
    setEditPaneSize(EDIT_PANE_COLLAPSED_SIZE);
  }, [hasEditTarget]);

  useEffect(() => {
    dispatchExpansion({
      type: 'reconcile',
      active: viewMode === 'tree',
      validGroupKeys: redisGroupKeys,
      automaticExpandedKeys: automaticallyExpandedGroupKeys,
      searchKey: appliedSearchKey.trim(),
    });
  }, [appliedSearchKey, automaticallyExpandedGroupKeys, redisGroupKeys, viewMode]);

  const getRedisDataItemDetail = (redisDataItem: RedisDataItem) => {
    const requestIdentity = getRedisDataItemIdentity(redisDataItem);
    if (!requestIdentity || selectedRowIdentityRef.current !== requestIdentity) {
      return;
    }
    if (redisDataItem.name === null) {
      setRedisDataItem(redisDataItem);
      return;
    }
    const keyName = redisDataItem.name;
    const requestId = detailRequestIdRef.current + 1;
    detailRequestIdRef.current = requestId;
    setRedisDataItem(null);
    setDetailLoadingKey(keyName);
    redisServer
      .queryRedisKeyDetail({
        dataSourceId: uniqueData.dataSourceId,
        databaseName: uniqueData.databaseName,
        keyName,
      })
      .then((res) => {
        if (
          detailRequestIdRef.current !== requestId ||
          selectedRowIdentityRef.current !== requestIdentity
        ) {
          return;
        }
        if (!res || (res as any).type === 'none') {
          setTableData(
            (current) =>
              current?.filter((item) => getRedisDataItemIdentity(item) !== requestIdentity) || [],
          );
          updateSelectedRowIdentity(null);
          setRedisDataItem(null);
          return;
        }
        const nextRedisDataItem = { ...redisDataItem, ...res };
        setTableData((current) => {
          if (
            detailRequestIdRef.current !== requestId ||
            selectedRowIdentityRef.current !== requestIdentity
          ) {
            return current;
          }
          return (
            current?.map((item) => {
              if (getRedisDataItemIdentity(item) === requestIdentity) {
                return nextRedisDataItem;
              }
              return item;
            }) || []
          );
        });
        setRedisDataItem((current) =>
          detailRequestIdRef.current === requestId &&
          selectedRowIdentityRef.current === requestIdentity
            ? nextRedisDataItem
            : current,
        );
      })
      .finally(() => {
        if (
          detailRequestIdRef.current === requestId &&
          selectedRowIdentityRef.current === requestIdentity
        ) {
          setDetailLoadingKey(null);
        }
      });
  };

  const loadScanBatch = (reset = false) => {
    if (!reset && (scanComplete || loadingMore)) {
      return;
    }
    const requestId = scanRequestIdRef.current + 1;
    scanRequestIdRef.current = requestId;
    const cursor = reset ? INITIAL_SCAN_CURSOR : scanCursor;
    if (reset) {
      editSessionRegistry.invalidateAll();
      activeEditSessionRef.current = null;
      setAppliedSearchKey(searchBarValue);
      setTableData(null);
      setPresenceDraft(false);
      setRedisDataItem(null);
      updateSelectedRowIdentity(null);
      setDetailLoadingKey(null);
      setScanCursor(INITIAL_SCAN_CURSOR);
      setScanComplete(false);
      setLoadingMore(false);
    } else {
      setLoadingMore(true);
    }
    redisServer
      .scanRedisKeys({
        dataSourceId: uniqueData.dataSourceId,
        databaseName: uniqueData.databaseName,
        searchKey: searchBarValue,
        cursor,
        count: REDIS_SCAN_COUNT,
      })
      .then((res) => {
        if (scanRequestIdRef.current !== requestId) {
          return;
        }
        const nextKeys = (res?.keys || [])
          .map((item) => {
            return { ...item };
          })
          .filter((item) => {
            const identity = getRedisDataItemIdentity(item);
            const deletedAtRequestId = identity
              ? deletedIdentityScanRequestRef.current.get(identity)
              : undefined;
            return deletedAtRequestId === undefined || requestId > deletedAtRequestId;
          });
        setScanCursor(res?.nextCursor || INITIAL_SCAN_CURSOR);
        setScanComplete(res?.complete || !res?.hasMore);
        setTableData((current) => {
          if (reset || !current) {
            return nextKeys;
          }
          return mergeRedisKeys(current, nextKeys);
        });
      })
      .catch(() => {
        if (scanRequestIdRef.current !== requestId) {
          return;
        }
        if (reset) {
          setTableData([]);
        }
      })
      .finally(() => {
        if (scanRequestIdRef.current === requestId) {
          setLoadingMore(false);
        }
      });
  };

  const getTableData = () => {
    loadScanBatch(true);
  };

  // Table column configuration.
  const columns = useMemo(() => {
    return [
      {
        title: i18n('redis.keyName'),
        name: 'name',
        lock: true,
        transitionValue: (value, rowData) =>
          rowData.kind === 'group' ? `${value} (${rowData.count})` : value,
      },
      {
        title: i18n('redis.type'),
        name: 'type',
      },
      // {
      //   title: i18n('redis.size'),
      //   name: 'size',
      // },
      {
        title: 'TTL',
        name: 'ttl',
        transitionValue: (value, rowData) => (rowData.kind === 'group' ? '' : formatRedisTtl(value)),
      },
    ];
  }, []);

  const handleAdd = () => {
    const newKey = {
      name: null,
      type: RedisFieldType.STRING,
      value: '',
      ttl: -1,
      isDraftFE: true,
    };
    const newTableData = [newKey, ...(tableData || [])];
    setTableData(newTableData);
    updateSelectedRowIdentity(REDIS_DRAFT_ROW_IDENTITY);
    setPresenceDraft(true);
    setViewMode('list');
    baseTableRef.current?.scrollToTop();
  };

  const handleCloseEditPane = () => {
    updateSelectedRowIdentity(null);
    setRedisDataItem(null);
    setDetailLoadingKey(null);
  };

  const handleViewModeChange = (value: string | number) => {
    const nextViewMode = value as RedisKeyViewMode;
    if (nextViewMode !== viewMode) {
      handleCloseEditPane();
    }
    setViewMode(nextViewMode);
    persistRedisKeyViewMode(
      getRedisViewModeStorage(),
      REDIS_KEY_VIEW_MODE_STORAGE_KEY,
      nextViewMode,
    );
  };

  const handleDelete = () => {
    const { name, isDraftFE } = selectedRedisDataItem || {};
    const deleteIdentity = selectedRedisDataItem
      ? getRedisDataItemIdentity(selectedRedisDataItem)
      : undefined;
    if (isDraftFE) {
      setTableData(tableData?.filter((item) => !item.isDraftFE) || []);
      updateSelectedRowIdentity(null);
      setPresenceDraft(false);
      setRedisDataItem(null);
      return;
    }
    if (name !== null && name !== undefined && deleteIdentity) {
      openUnifiedDeletion({
        title: `${i18n('redis.tip.deleteKey', name)}`,
        okCallBack: () => {
          redisServer
            .deleteRedisData({
              dataSourceId: uniqueData.dataSourceId,
              databaseName: uniqueData.databaseName,
              keyName: name,
            })
            .then(() => {
              deletedIdentityScanRequestRef.current.set(deleteIdentity, scanRequestIdRef.current);
              if (selectedRowIdentityRef.current === deleteIdentity) {
                updateSelectedRowIdentity(null);
              }
              setTableData((current) =>
                current
                  ? current.filter((item) => getRedisDataItemIdentity(item) !== deleteIdentity)
                  : current,
              );
            });
        },
      });
    }
  };

  const editSession = activeEditSessionRef.current;
  const editDataSubmitSuccess = (_redisDataItem) => {
    if (!_redisDataItem) {
      return;
    }
    if (!editSession || !editSessionRegistry.isLatest(editSession)) {
      return;
    }
    const newRedisDataItem = { ..._redisDataItem };
    delete newRedisDataItem.isDraftFE;
    const savedIdentity = getRedisDataItemIdentity(newRedisDataItem);
    if (!savedIdentity) {
      return;
    }
    // Replace the selected row in tableData with the current data.
    setTableData((current) => {
      if (!current || !editSessionRegistry.isLatest(editSession)) {
        return current;
      }
      if (editSession.identity === REDIS_DRAFT_ROW_IDENTITY) {
        return [
          newRedisDataItem,
          ...current.filter((item) => {
            const identity = getRedisDataItemIdentity(item);
            return identity !== REDIS_DRAFT_ROW_IDENTITY && identity !== savedIdentity;
          }),
        ];
      }
      return current.map((item) =>
        getRedisDataItemIdentity(item) === editSession.identity ? newRedisDataItem : item,
      );
    });
    if (editSession.identity === REDIS_DRAFT_ROW_IDENTITY) {
      setPresenceDraft((current) => (editSessionRegistry.isLatest(editSession) ? false : current));
    }
    if (
      selectedRowIdentityRef.current !== editSession.identity ||
      activeEditSessionRef.current !== editSession ||
      !editSessionRegistry.isLatest(editSession)
    ) {
      return;
    }
    updateSelectedRowIdentity(savedIdentity);
    setRedisDataItem(newRedisDataItem);
  };

  return (
    <SplitPaneAny
      split="horizontal"
      onChange={(newSize) => {
        if (!hasEditTarget) {
          return;
        }
        setEditPaneSize(newSize < EDIT_PANE_COLLAPSE_THRESHOLD ? EDIT_PANE_COLLAPSED_SIZE : newSize);
      }}
      className={cx(
        { ResizerSizeIsZeroTop: editPaneSize === EDIT_PANE_COLLAPSED_SIZE },
        { ResizerHidden: !hasEditTarget },
        styles.redisAllData,
        className,
      )}
      size={hasEditTarget ? editPaneSize : EDIT_PANE_COLLAPSED_SIZE}
      minSize={EDIT_PANE_COLLAPSED_SIZE}
      primary="second"
      allowResize={hasEditTarget}
      pane1Style={{ height: '0px' }}
      pane2Style={{ display: hasEditTarget ? 'block' : 'none' }}
    >
      <div className={styles.upperBox}>
        <div className={styles.operationAllDataBar}>
          <div className={styles.left}>
            <ToolbarBtn
              prefixIcon="icon-add"
              text={i18n('redis.button.addKey')}
              disabled={presenceDraft}
              onClick={handleAdd}
            />
            <ToolbarBtn
              prefixIcon="icon-minus"
              text={i18n('redis.button.deleteKey')}
              disabled={!selectedRedisDataItem}
              onClick={handleDelete}
            />
            <ToolbarBtn prefixIcon="icon-refresh" text={i18n('common.button.refresh')} onClick={getTableData} />
            <ToolbarBtn
              text={
                loadingMore
                  ? i18n('common.text.loading')
                  : scanComplete
                  ? i18n('common.text.noMore')
                  : i18n('redis.button.loadMore')
              }
              disabled={tableData === null || scanComplete || loadingMore || presenceDraft}
              onClick={() => loadScanBatch(false)}
            />
          </div>
          <div className={styles.right}>
            <Segmented
              className={styles.viewMode}
              size="small"
              value={viewMode}
              onChange={handleViewModeChange}
              options={[
                {
                  value: 'list',
                  label: (
                    <Tooltip title={i18n('redis.viewMode.list')}>
                      <span className={styles.viewModeIcon} aria-label={i18n('redis.viewMode.list')}>
                        <List size={14} />
                      </span>
                    </Tooltip>
                  ),
                },
                {
                  value: 'tree',
                  label: (
                    <Tooltip title={i18n('redis.viewMode.tree')}>
                      <span className={styles.viewModeIcon} aria-label={i18n('redis.viewMode.tree')}>
                        <ListTree size={14} />
                      </span>
                    </Tooltip>
                  ),
                },
              ]}
            />
            <SearchBar
              className={styles.searchBar}
              placeholder={i18n('common.text.search')}
              value={searchBarValue}
              onChange={(e) => {
                setSearchBarValue(e.target.value);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  getTableData();
                }
              }}
            />
          </div>
        </div>
        <BaseTable
          ref={baseTableRef}
          className={styles.tableBox}
          loading={tableData === null}
          tableData={viewMode === 'tree' ? redisKeyTreeData : tableData}
          columns={columns}
          sizes={REDIS_TABLE_COLUMN_SIZES}
          selectedRows={selectedRows}
          onSelectedRowsChange={handleSelectedRowsChange}
          onActivateRow={handleActivateRedisRow}
          onEscapeKey={handleCloseEditPane}
          primaryKey={viewMode === 'tree' ? 'key' : undefined}
          treeMode={viewMode === 'tree' ? redisTreeMode : undefined}
          getRowIndex={viewMode === 'tree' ? getRedisTreeRowIndex : undefined}
        />
      </div>
      <div className={styles.editDataSide}>
        <Tooltip title={i18n('redis.button.closeEditPane')}>
          <Button
            className={styles.closeEditPane}
            type="text"
            size="small"
            aria-label={i18n('redis.button.closeEditPane')}
            icon={<X size={14} />}
            onClick={handleCloseEditPane}
          />
        </Tooltip>
        {curRedisDataItem ? (
          <EditData
            dataSourceId={uniqueData.dataSourceId}
            databaseName={uniqueData.databaseName}
            redisDataItem={curRedisDataItem}
            submitSuccess={editDataSubmitSuccess}
          />
        ) : (
          <div className={styles.emptyStatus}>
            {detailLoadingKey ? i18n('common.text.loading') : i18n('redis.editData.emptyStatus')}
          </div>
        )}
      </div>
    </SplitPaneAny>
  );
};

export default memo<IProps>(RedisAllData);
