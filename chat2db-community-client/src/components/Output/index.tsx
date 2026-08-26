import React, { memo, useCallback, useMemo, useRef, useState } from 'react';
import classnames from 'classnames';
import ScrollLoading from '@/components/ScrollLoading';
import historyService, { IHistoryRecord, OperationTypeEnum } from '@/service/history';
import i18n from '@/i18n';
import { useStyles } from './style';
import { IconButton, IconfontSvg, staticMessage } from '@chat2db/ui';
import { Tooltip } from 'antd';
import { ConsoleStatus, getDatabaseInfo, WorkspaceTabType } from '@/constants';
import { useWorkspaceStore } from '@/store/workspace';
import { copyToClipboard, getTemporaryId } from '@/utils';
import { useTreeStore } from '@/store/tree';
import { TreeNodeData } from '@/typings';
import { Copy, RotateCw, SquareArrowOutUpRight } from 'lucide-react';
import PanelToolbar, { PANEL_TOOLBAR_BUTTON_SIZE } from '@/components/PanelToolbar';

interface IProps {
  className?: string;
  headerLeading?: React.ReactNode;
}

type IDatasource = IHistoryRecord;

function normalizeStatus(status?: string | null) {
  return String(status || '').toLowerCase();
}

function isSuccessStatus(status?: string | null) {
  const normalizedStatus = normalizeStatus(status);
  return !normalizedStatus || normalizedStatus === 'success' || normalizedStatus === 'successful';
}

function getSqlSummary(sql?: string | null) {
  const normalizedSql = (sql || '').replace(/\s+/g, ' ').trim();
  return normalizedSql || '--';
}

function getHistorySourceKey(item: IDatasource) {
  return item.dataSourceId ? String(item.dataSourceId) : '';
}

function getHistoryDataSourceFallback(item: IDatasource) {
  return item.dataSourceId ? `DataSource #${item.dataSourceId}` : '-';
}

function getHistoryDataSourceName(item: IDatasource, sourceInfo?: TreeNodeData, cachedSourceName?: string) {
  return (
    item.dataSourceName ||
    cachedSourceName ||
    sourceInfo?.extraParams?.dataSourceName ||
    getHistoryDataSourceFallback(item)
  );
}

function getHistoryTitle(item: IDatasource, sourceInfo?: TreeNodeData, cachedSourceName?: string) {
  const dataSourceName =
    getHistoryDataSourceName(item, sourceInfo, cachedSourceName);
  const nameList = [dataSourceName, item.databaseName || item.schemaName].filter(Boolean);
  return nameList.join(' / ');
}

function getHistoryPopover(item: IDatasource, sourceInfo?: TreeNodeData, cachedSourceName?: string) {
  return [
    getHistoryTitle(item, sourceInfo, cachedSourceName),
    item.gmtCreate,
  ].filter(Boolean).join('\n');
}

export default memo<IProps>((props) => {
  const {
    styles,
    theme: { appearance },
  } = useStyles();
  const { className, headerLeading } = props;
  const addWorkspaceTab = useWorkspaceStore((state) => state.addWorkspaceTab);
  const workspaceTabList = useWorkspaceStore((state) => state.workspaceTabList);
  const savedConsoleList = useWorkspaceStore((state) => state.savedConsoleList);
  const dataSourceList = useTreeStore((state) => state.dataSourceList);
  const [dataSource, setDataSource] = useState<IDatasource[]>([]);
  const [finished, setFinished] = useState(false);
  const outputContentRef = useRef<HTMLDivElement>(null);
  const curPageRef = useRef(1);
  const loadingRef = useRef(false);
  const finishedRef = useRef(false);

  const dataSourceInfoMap = useMemo(() => {
    return (dataSourceList || []).reduce<Record<string, TreeNodeData>>((map, item) => {
      if (item.extraParams?.dataSourceId) {
        map[String(item.extraParams.dataSourceId)] = item;
      }
      return map;
    }, {});
  }, [dataSourceList]);

  const dataSourceNameMap = useMemo(() => {
    const map: Record<string, string> = {};
    (dataSourceList || []).forEach((item) => {
      const dataSourceId = item.extraParams?.dataSourceId;
      const dataSourceName = item.extraParams?.dataSourceName;
      if (dataSourceId && dataSourceName) {
        map[String(dataSourceId)] = dataSourceName;
      }
    });
    (workspaceTabList || []).forEach((item) => {
      const dataSourceId = item.uniqueData?.dataSourceId;
      const dataSourceName = item.uniqueData?.dataSourceName;
      if (dataSourceId && dataSourceName && !map[String(dataSourceId)]) {
        map[String(dataSourceId)] = dataSourceName;
      }
    });
    (savedConsoleList || []).forEach((item) => {
      if (item.dataSourceId && item.dataSourceName && !map[String(item.dataSourceId)]) {
        map[String(item.dataSourceId)] = item.dataSourceName;
      }
    });
    return map;
  }, [dataSourceList, savedConsoleList, workspaceTabList]);

  const getFullHistoryRecord = useCallback(async (item: IDatasource) => {
    if (item.more && item.id) {
      return historyService.getHistoryDetail({ id: item.id });
    }
    return item;
  }, []);

  const getHistoryList = useCallback(async () => {
    if (loadingRef.current || finishedRef.current) {
      return;
    }
    loadingRef.current = true;
    try {
      const res = await historyService.getHistoryList({
        pageNo: curPageRef.current,
        pageSize: 40,
        operationType: OperationTypeEnum.SQL_EXECUTE,
      });

      curPageRef.current += 1;
      finishedRef.current = !res.hasNextPage;
      setFinished(finishedRef.current);
      setDataSource((prev) => [...prev, ...((res.data || []) as IDatasource[])]);
    } finally {
      loadingRef.current = false;
    }
  }, []);

  const refresh = useCallback(() => {
    curPageRef.current = 1;
    loadingRef.current = false;
    finishedRef.current = false;
    setFinished(false);
    setDataSource([]);
    getHistoryList();
  }, [getHistoryList]);

  const openHistoryConsole = useCallback(
    async (item: IDatasource, readOnly: boolean) => {
      const detail = await getFullHistoryRecord(item);
      const tabId = getTemporaryId(`${readOnly ? 'execution-log' : 'execution-log-copy'}-${item.id || Date.now()}`);
      const sourceInfo =
        dataSourceInfoMap[getHistorySourceKey(detail)] || dataSourceInfoMap[getHistorySourceKey(item)];
      const cachedSourceName =
        dataSourceNameMap[getHistorySourceKey(detail)] || dataSourceNameMap[getHistorySourceKey(item)];
      const dataSourceName = getHistoryDataSourceName(detail, sourceInfo, cachedSourceName);
      const title = getHistoryTitle(detail, sourceInfo, cachedSourceName);
      const popoverContent = getHistoryPopover(detail, sourceInfo, cachedSourceName);

      addWorkspaceTab({
        id: tabId,
        type: WorkspaceTabType.CONSOLE,
        title,
        uniqueData: {
          consoleId: readOnly ? tabId : undefined,
          dataSourceId: detail.dataSourceId || undefined,
          dataSourceName: dataSourceName === '-' ? undefined : dataSourceName,
          databaseType: detail.type || sourceInfo?.extraParams?.databaseType || undefined,
          databaseName: detail.databaseName || undefined,
          schemaName: detail.schemaName || undefined,
          status: ConsoleStatus.DRAFT,
          ddl: detail.ddl || '',
          connectable: detail.connectable ?? undefined,
          popoverContent,
          readOnly,
        },
      });
    },
    [addWorkspaceTab, dataSourceInfoMap, dataSourceNameMap, getFullHistoryRecord],
  );

  const openHistoryTab = useCallback(
    (item: IDatasource) => openHistoryConsole(item, true),
    [openHistoryConsole],
  );

  const openEditableHistoryTab = useCallback(
    (event: React.MouseEvent, item: IDatasource) => {
      event.stopPropagation();
      return openHistoryConsole(item, false);
    },
    [openHistoryConsole],
  );

  const copyHistorySql = useCallback(
    async (event: React.MouseEvent, item: IDatasource) => {
      event.stopPropagation();
      const detail = await getFullHistoryRecord(item);
      copyToClipboard(detail.ddl || '');
      staticMessage.success(i18n('common.button.copySuccessfully'));
    },
    [getFullHistoryRecord],
  );

  const renderDatabaseIcon = useCallback(
    (item: IDatasource) => {
      const sourceInfo = dataSourceInfoMap[getHistorySourceKey(item)];
      const databaseInfo = getDatabaseInfo(item.type || sourceInfo?.extraParams?.databaseType);
      if (!databaseInfo?.icon) {
        return <IconfontSvg className={styles.databaseFallbackIcon} size={18} code="icon-chat-database" />;
      }

      return (
        <IconfontSvg
          className={styles.databaseIconSvg}
          size={22}
          existDark={databaseInfo.iconExistDark}
          appearance={appearance}
          code={databaseInfo.icon}
        />
      );
    },
    [appearance, dataSourceInfoMap, styles.databaseFallbackIcon, styles.databaseIconSvg],
  );

  const emptyContent = useMemo(() => {
    if (dataSource.length || !finished) {
      return null;
    }
    return <div className={styles.emptyContent}>{i18n('common.text.noData')}</div>;
  }, [dataSource.length, finished, styles.emptyContent]);

  return (
    <div className={classnames(styles.output, className)}>
      <PanelToolbar
        leading={headerLeading ?? <span>{i18n('common.title.executiveLogging')}</span>}
        trailing={<IconButton size={PANEL_TOOLBAR_BUTTON_SIZE} icon={RotateCw} onClick={refresh} />}
      />
      <div className={styles.outputContent} ref={outputContentRef}>
        <ScrollLoading
          onReachBottom={getHistoryList}
          scrollerElement={outputContentRef}
          threshold={300}
          finished={finished}
        >
          <>
            {dataSource.map((item) => {
              const sourceInfo = dataSourceInfoMap[getHistorySourceKey(item)];
              const dataSourceName = getHistoryDataSourceName(
                item,
                sourceInfo,
                dataSourceNameMap[getHistorySourceKey(item)],
              );
              const sqlScope = [item.databaseName, item.schemaName].filter(Boolean).join(' / ');
              const statusIsSuccess = isSuccessStatus(item.status);
              return (
                <div
                  key={item.id || `${item.gmtCreate}-${item.dataSourceName}-${item.ddl}`}
                  className={styles.outputItem}
                  onClick={() => openHistoryTab(item)}
                >
                  <div className={styles.recordMain}>
                    <div className={styles.databaseIcon}>{renderDatabaseIcon(item)}</div>
                    <div className={styles.recordInfo}>
                      <div className={styles.datasourceLine}>
                        <Tooltip title={dataSourceName}>
                          <span className={styles.datasourceName}>{dataSourceName}</span>
                        </Tooltip>
                        {sqlScope && (
                          <Tooltip title={sqlScope}>
                            <span className={styles.sqlScope}>{sqlScope}</span>
                          </Tooltip>
                        )}
                      </div>
                      <div className={styles.sqlSummary}>{getSqlSummary(item.ddl)}</div>
                      <div className={styles.metaLine}>
                        <span>{item.gmtCreate}</span>
                        {!!item.useTime && <span>{i18n('common.text.executionTime', item.useTime)}</span>}
                        {!!item.operationRows && <span>{item.operationRows} rows</span>}
                        <span
                          className={classnames(styles.statusText, {
                            [styles.failureStatusText]: !statusIsSuccess,
                          })}
                        >
                          {statusIsSuccess ? i18n('common.text.successful') : i18n('common.text.failure')}
                        </span>
                      </div>
                    </div>
                  </div>
                  <div className={classnames(styles.recordActions, 'output-record-actions')}>
                    <IconButton
                      className={styles.actionButton}
                      icon={SquareArrowOutUpRight}
                      size={{ boxSize: 20, iconSize: 14, borderRadius: 4, strokeWidth: 2 }}
                      title={i18n('common.button.openInNewConsole')}
                      onClick={(event) => openEditableHistoryTab(event, item)}
                    />
                    <IconButton
                      className={styles.actionButton}
                      icon={Copy}
                      size={{ boxSize: 20, iconSize: 14, borderRadius: 4, strokeWidth: 2 }}
                      title={i18n('common.button.copy')}
                      onClick={(event) => copyHistorySql(event, item)}
                    />
                  </div>
                </div>
              );
            })}
            {emptyContent}
          </>
        </ScrollLoading>
      </div>
    </div>
  );
});
