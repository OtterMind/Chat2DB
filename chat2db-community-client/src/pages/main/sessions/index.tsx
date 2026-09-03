import { Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { RefreshCw, Search, ShieldAlert, Square, Unplug } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import SelectBoundInfo, { BoundInfo } from '@/components/SelectBoundInfo';
import { DatabaseTypeCode } from '@/constants';
import i18n from '@/i18n';
import sqlService, { DbSessionKillType, IDbSession } from '@/service/sql';
import { useWorkspaceStore } from '@/store/workspace';
import feedback from '@/utils/feedback';

import {
  createKillSessionRequest,
  createSessionRequest,
  filterDbSessions,
  formatKillOutcomeResult,
  formatKillSessionSql,
  isKillActionDisabled,
  SESSION_AUTO_REFRESH_INTERVALS,
  SESSION_DATABASE_EMPTY_VALUE,
  ISessionFilters,
} from './sessionMonitorUtils';
import { useStyles } from './style';

const SessionMonitor = () => {
  const { styles } = useStyles();
  const [modal, contextHolder] = Modal.useModal();
  const currentConnectionDetails = useWorkspaceStore((state) => state.currentConnectionDetails);
  const [boundInfo, setBoundInfo] = useState<BoundInfo>({});
  const [sessions, setSessions] = useState<IDbSession[]>([]);
  const [filters, setFilters] = useState<ISessionFilters>({});
  const [autoRefreshSeconds, setAutoRefreshSeconds] = useState(0);
  const [loading, setLoading] = useState(false);
  const [lastResult, setLastResult] = useState('');
  const loadingRef = useRef(false);

  useEffect(() => {
    if (boundInfo.dataSourceId || !currentConnectionDetails?.id) {
      return;
    }
    setBoundInfo({
      dataSourceId: currentConnectionDetails.id,
      dataSourceName: currentConnectionDetails.alias,
      databaseType: currentConnectionDetails.type,
      environmentId: currentConnectionDetails.environmentId,
      environment: currentConnectionDetails.environment,
      identityColor: currentConnectionDetails.identityColor,
      watermarkEnabled: currentConnectionDetails.watermarkEnabled,
      watermarkContent: currentConnectionDetails.watermarkContent,
    });
  }, [boundInfo.dataSourceId, currentConnectionDetails]);

  const sessionRequest = useMemo(
    () => createSessionRequest(boundInfo.dataSourceId, boundInfo.databaseName),
    [boundInfo.dataSourceId, boundInfo.databaseName],
  );
  const filteredSessions = useMemo(() => filterDbSessions(sessions, filters), [filters, sessions]);
  const isMysqlDataSource = boundInfo.databaseType === DatabaseTypeCode.MYSQL;
  const userFilterOptions = useMemo(() => createFilterOptions(sessions.map((session) => session.user)), [sessions]);
  const databaseFilterOptions = useMemo(
    () => createFilterOptions(sessions.map((session) => session.db || SESSION_DATABASE_EMPTY_VALUE)),
    [sessions],
  );
  const stateFilterOptions = useMemo(
    () => createFilterOptions(sessions.map((session) => session.state || SESSION_DATABASE_EMPTY_VALUE)),
    [sessions],
  );
  const autoRefreshOptions = useMemo(
    () =>
      SESSION_AUTO_REFRESH_INTERVALS.map((seconds) => ({
        value: seconds,
        label:
          seconds > 0
            ? i18n('sessionMonitor.refresh.intervalSeconds', seconds)
            : i18n('sessionMonitor.refresh.off'),
      })),
    [],
  );

  const loadSessions = useCallback(async () => {
    if (!sessionRequest) {
      setSessions([]);
      return;
    }
    if (loadingRef.current) {
      return;
    }

    loadingRef.current = true;
    setLoading(true);
    try {
      const result = await sqlService.getSessionList(sessionRequest);
      setSessions(result || []);
    } catch {
      setSessions([]);
      feedback.error(i18n('sessionMonitor.message.loadFailed'));
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }, [sessionRequest]);

  useEffect(() => {
    if (sessionRequest && isMysqlDataSource) {
      void loadSessions();
      return;
    }
    setSessions([]);
  }, [isMysqlDataSource, loadSessions, sessionRequest]);

  useEffect(() => {
    if (!sessionRequest || !isMysqlDataSource || autoRefreshSeconds <= 0) {
      return undefined;
    }

    const timer = window.setInterval(() => {
      void loadSessions();
    }, autoRefreshSeconds * 1000);
    return () => window.clearInterval(timer);
  }, [autoRefreshSeconds, isMysqlDataSource, loadSessions, sessionRequest]);

  const updateFilter = useCallback(<K extends keyof ISessionFilters>(key: K, value: ISessionFilters[K]) => {
    setFilters((previous) => ({
      ...previous,
      [key]: value,
    }));
  }, []);

  const confirmKill = useCallback(
    (session: IDbSession, killType: DbSessionKillType) => {
      if (!sessionRequest) {
        feedback.warning(i18n('workspace.text.pleaseSelectDataSource'));
        return;
      }
      if (isKillActionDisabled(session)) {
        feedback.warning(i18n('sessionMonitor.action.currentDisabled'));
        return;
      }

      const isConnectionKill = killType === 'CONNECTION';
      const sqlPreview = formatKillSessionSql(session, killType);
      modal.confirm({
        title: isConnectionKill
          ? i18n('sessionMonitor.confirm.connectionTitle')
          : i18n('sessionMonitor.confirm.queryTitle'),
        content: (
          <Space direction="vertical" size={8}>
            <span>
              {i18n(
                isConnectionKill ? 'sessionMonitor.confirm.connectionContent' : 'sessionMonitor.confirm.queryContent',
                session.id,
                session.user,
              )}
            </span>
            <span>
              {i18n('sessionMonitor.confirm.sqlPreview')}: <Typography.Text code>{sqlPreview}</Typography.Text>
            </span>
          </Space>
        ),
        okText: isConnectionKill ? i18n('sessionMonitor.action.killConnection') : i18n('sessionMonitor.action.killQuery'),
        okButtonProps: { danger: true },
        cancelText: i18n('common.button.cancel'),
        onOk: async () => {
          const result = await sqlService.killSession(createKillSessionRequest(sessionRequest, session, killType));
          setLastResult(result ? formatKillOutcomeResult(result) : sqlPreview);
          if (result?.status === 'ALREADY_FINISHED') {
            feedback.info(i18n('sessionMonitor.message.killAlreadyFinished'));
          } else {
            feedback.success(i18n('sessionMonitor.message.killSuccess'));
          }
          await loadSessions();
        },
      });
    },
    [loadSessions, modal, sessionRequest],
  );

  const columns = useMemo<ColumnsType<IDbSession>>(
    () => [
      {
        title: 'ID',
        dataIndex: 'id',
        width: 96,
        sorter: (left, right) => left.id - right.id,
        render: (value: number, session) => (
          <Space size={6}>
            <span>{value}</span>
            {session.current && <Tag color="processing">{i18n('sessionMonitor.tag.current')}</Tag>}
          </Space>
        ),
      },
      {
        title: i18n('sessionMonitor.column.user'),
        dataIndex: 'user',
        width: 180,
      },
      {
        title: i18n('sessionMonitor.column.host'),
        dataIndex: 'host',
        width: 220,
      },
      {
        title: i18n('sessionMonitor.column.database'),
        dataIndex: 'db',
        width: 160,
        render: (value: string | null) => value || '-',
      },
      {
        title: i18n('sessionMonitor.column.command'),
        dataIndex: 'command',
        width: 120,
        render: (value: string) => <Tag>{value || '-'}</Tag>,
      },
      {
        title: i18n('sessionMonitor.column.time'),
        dataIndex: 'time',
        width: 110,
        sorter: (left, right) => left.time - right.time,
      },
      {
        title: i18n('sessionMonitor.column.state'),
        dataIndex: 'state',
        width: 180,
        render: (value: string | null) => value || '-',
      },
      {
        title: i18n('sessionMonitor.column.info'),
        dataIndex: 'info',
        ellipsis: true,
        render: (value: string | null) => (
          <Tooltip title={value || ''}>
            <span className={styles.codeCell}>{value || '-'}</span>
          </Tooltip>
        ),
      },
      {
        title: i18n('common.text.action'),
        key: 'action',
        fixed: 'right',
        width: 210,
        render: (_, session) => {
          const disabled = isKillActionDisabled(session);
          return (
            <Space size={6}>
              <Tooltip title={disabled ? i18n('sessionMonitor.action.currentDisabled') : undefined}>
                <Button
                  disabled={disabled}
                  icon={<Square size={14} />}
                  size="small"
                  onClick={() => confirmKill(session, 'QUERY')}
                >
                  {i18n('sessionMonitor.action.killQuery')}
                </Button>
              </Tooltip>
              <Tooltip title={disabled ? i18n('sessionMonitor.action.currentDisabled') : undefined}>
                <Button
                  danger
                  disabled={disabled}
                  icon={<Unplug size={14} />}
                  size="small"
                  onClick={() => confirmKill(session, 'CONNECTION')}
                >
                  {i18n('sessionMonitor.action.killConnection')}
                </Button>
              </Tooltip>
            </Space>
          );
        },
      },
    ],
    [confirmKill, styles.codeCell],
  );

  return (
    <div className={styles.container}>
      {contextHolder}
      <div className={styles.header}>
        <div className={styles.titleGroup}>
          <div className={styles.title}>{i18n('sessionMonitor.title')}</div>
          <div className={styles.subtitle}>{i18n('sessionMonitor.subtitle')}</div>
        </div>
        <div className={styles.toolbar}>
          <Input
            allowClear
            prefix={<Search size={14} />}
            placeholder={i18n('sessionMonitor.filter.placeholder')}
            value={filters.keyword}
            onChange={(event) => updateFilter('keyword', event.target.value)}
            style={{ width: 220 }}
          />
          <Select
            allowClear
            options={userFilterOptions}
            placeholder={i18n('sessionMonitor.filter.user')}
            value={filters.user}
            onChange={(value) => updateFilter('user', value)}
            style={{ width: 160 }}
          />
          <Select
            allowClear
            options={databaseFilterOptions}
            placeholder={i18n('sessionMonitor.filter.database')}
            value={filters.database}
            onChange={(value) => updateFilter('database', value)}
            style={{ width: 160 }}
          />
          <Select
            allowClear
            options={stateFilterOptions}
            placeholder={i18n('sessionMonitor.filter.state')}
            value={filters.state}
            onChange={(value) => updateFilter('state', value)}
            style={{ width: 160 }}
          />
          <InputNumber
            min={0}
            placeholder={i18n('sessionMonitor.filter.minDuration')}
            value={filters.minDurationSeconds ?? null}
            onChange={(value) => updateFilter('minDurationSeconds', typeof value === 'number' ? value : null)}
            style={{ width: 150 }}
          />
          <Select
            options={autoRefreshOptions}
            value={autoRefreshSeconds}
            onChange={setAutoRefreshSeconds}
            style={{ width: 150 }}
          />
          <Button
            icon={<RefreshCw size={14} />}
            disabled={!sessionRequest || !isMysqlDataSource}
            loading={loading}
            onClick={loadSessions}
          >
            {i18n('common.button.refresh')}
          </Button>
        </div>
      </div>
      <div className={styles.selector}>
        <SelectBoundInfo
          boundInfo={boundInfo}
          onChangeDBInfo={(nextBoundInfo) => {
            setBoundInfo(nextBoundInfo);
            setLastResult('');
          }}
          allowEmpty
          requireDataSource
        />
      </div>
      {lastResult && <div className={styles.result}>{i18n('sessionMonitor.message.lastResult', lastResult)}</div>}
      <div className={styles.body}>
        {!sessionRequest ? (
          <div className={styles.tableWrap}>{i18n('workspace.text.pleaseSelectDataSource')}</div>
        ) : !isMysqlDataSource ? (
          <div className={styles.tableWrap}>
            <ShieldAlert size={18} /> {i18n('sessionMonitor.message.mysqlOnly')}
          </div>
        ) : (
          <div className={styles.tableWrap}>
            <Table
              rowKey="id"
              loading={loading}
              columns={columns}
              dataSource={filteredSessions}
              pagination={{ size: 'small', showSizeChanger: true }}
              scroll={{ x: 1480, y: 'calc(100vh - 300px)' }}
              size="small"
            />
          </div>
        )}
      </div>
    </div>
  );
};

function createFilterOptions(values: Array<string | null | undefined>) {
  return Array.from(new Set(values.filter(Boolean) as string[])).map((value) => ({
    value,
    label: value,
  }));
}

export default SessionMonitor;
