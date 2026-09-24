import jcefApi from '@/jcef';
import i18n from '@/i18n';
import ConnectionServer from '@/service/connection';
import type { IConnectionDetails } from '@/typings/connection';
import type { SqlxDatasourceState, SqlxImportSummary, SqlxPlatform, SqlxStatus } from '@/typings/settings';
import { copyToClipboard } from '@/utils/copy';
import { isDesktop } from '@/utils/env';
import feedback from '@/utils/feedback';
import { Alert, Button, Table, Tag, Tooltip } from 'antd';
import { RefreshCw, Sparkles, Database, Terminal } from 'lucide-react';
import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { useStyles } from '../BaseSetting/style';
import {
  SQLX_INSTALL_DOCS_URL,
  SQLX_SKILL_COMMANDS,
  detectPlatform,
  manualInstallOptions,
  pathHintCommand,
} from './sqlxCommands';
import {
  datasourceStateColor,
  datasourceStateHintKey,
  datasourceStateLabelKey,
  isDatasourceSelectable,
} from './sqlxDatasourceStates';
import {
  canStartSqlxOperation,
  createSqlxOperationId,
  getSqlxErrorMessage,
  initialSqlxLifecycleState,
  isSqlxOperationRunning,
  reduceSqlxLifecycleState,
} from './sqlxLifecycle';
import { useSqlxStyles } from './style';

const POLL_INTERVAL_MS = 500;

/**
 * A redetect answers from the desktop cache, often in a few milliseconds; without a floor the spinner
 * would come and go inside one frame and the click would look ignored.
 */
const REDETECT_MIN_VISIBLE_MS = 400;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

/** Reason codes the import reports for entries that could not be copied. */
const SKIP_REASON_KEYS: Record<string, Parameters<typeof i18n>[0]> = {
  engine_not_supported: 'setting.sqlx.skip.engine_not_supported',
  invalid_port: 'setting.sqlx.skip.invalid_port',
  missing_host: 'setting.sqlx.skip.missing_host',
  invalid_path: 'setting.sqlx.skip.invalid_path',
  invalid_name: 'setting.sqlx.skip.invalid_name',
  duplicate_name: 'setting.sqlx.skip.duplicate_name',
  invalid_connection: 'setting.sqlx.skip.invalid_connection',
};

function skipReasonText(reason: string | undefined): string {
  const key = reason ? SKIP_REASON_KEYS[reason] : undefined;
  return key ? i18n(key) : reason ?? '';
}

/** The selected file path in a `select-file` result, mirroring the connection form's resolver. */
/** Attempts while the desktop bridge is still being registered during application startup. */
const STATUS_ATTEMPTS = 10;
const STATUS_RETRY_MS = 700;

async function fetchSqlxStatus(): Promise<SqlxStatus> {
  let lastError: unknown;
  for (let attempt = 0; attempt < STATUS_ATTEMPTS; attempt += 1) {
    try {
      return await jcefApi.getSqlxStatus();
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => {
        window.setTimeout(resolve, STATUS_RETRY_MS);
      });
    }
  }
  throw lastError;
}

function resolveSelectedFilePath(data: unknown): string | undefined {
  if (!data) {
    return undefined;
  }
  if (typeof data === 'string') {
    return data;
  }
  if (Array.isArray(data)) {
    for (const item of data) {
      const filePath = resolveSelectedFilePath(item);
      if (filePath) {
        return filePath;
      }
    }
    return undefined;
  }
  if (typeof data !== 'object') {
    return undefined;
  }
  const record = data as Record<string, unknown>;
  const nested = resolveSelectedFilePath(record.data);
  if (nested) {
    return nested;
  }
  for (const key of ['filePath', 'path', 'fileName']) {
    const value = record[key];
    if (typeof value === 'string' && value) {
      return value;
    }
  }
  return undefined;
}

export default function SqlxSetting() {
  const { styles } = useStyles();
  const { styles: sqlxStyles } = useSqlxStyles();
  const [lifecycle, dispatch] = useReducer(reduceSqlxLifecycleState, initialSqlxLifecycleState);
  const activeOperationIdRef = useRef<string | null>(null);
  const [showManualInstall, setShowManualInstall] = useState(false);
  const [redetecting, setRedetecting] = useState(false);
  const [dataSources, setDataSources] = useState<IConnectionDetails[]>([]);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [importing, setImporting] = useState(false);
  const [importSummary, setImportSummary] = useState<SqlxImportSummary | null>(null);
  const [importError, setImportError] = useState<string | null>(null);
  const [datasourceStates, setDatasourceStates] = useState<Record<number, SqlxDatasourceState>>({});

  const status = lifecycle.status;
  const platform: SqlxPlatform = status?.platform ?? detectPlatform();
  const running = isSqlxOperationRunning(status);
  /** Only a real operation blocks the other buttons; a redetect is a plain status refresh. */
  const busy = running || lifecycle.pending !== null;

  const applyStatus = useCallback((next: SqlxStatus) => {
    // Only a running operation blocks the next one; a finished or failed step must release the guard.
    activeOperationIdRef.current = isSqlxOperationRunning(next) ? next.operation?.operationId ?? null : null;
    dispatch({ type: 'STATUS', status: next });
  }, []);

  const refresh = useCallback(async () => {
    try {
      applyStatus(await fetchSqlxStatus());
    } catch (error) {
      dispatch({
        type: 'FAILURE',
        operationId: activeOperationIdRef.current ?? 'status',
        error: getSqlxErrorMessage(error),
      });
    }
  }, [applyStatus]);

  const loadDataSources = useCallback(async () => {
    try {
      const page = await ConnectionServer.getList({ pageNo: 1, pageSize: 200 });
      setDataSources(page?.data ?? []);
    } catch {
      setDataSources([]);
    }
  }, []);

  useEffect(() => {
    void refresh();
    void loadDataSources();
  }, [loadDataSources, refresh]);

  const loadDatasourceStates = useCallback(async (ids: number[]) => {
    if (ids.length === 0) {
      setDatasourceStates({});
      return;
    }
    try {
      const states = await jcefApi.getSqlxDatasourceStates({ ids });
      setDatasourceStates(Object.fromEntries((states ?? []).map((state) => [state.id, state])));
    } catch {
      setDatasourceStates({});
    }
  }, []);

  useEffect(() => {
    if (status?.state !== 'installed') {
      setDatasourceStates({});
      return;
    }
    void loadDatasourceStates(dataSources.map((source) => source.id));
  }, [dataSources, loadDatasourceStates, status?.state]);

  // A row SQLX cannot take must not stay selected, or the import would report it as skipped.
  useEffect(() => {
    setSelectedIds((ids) => ids.filter((id) => isDatasourceSelectable(datasourceStates[id]?.state)));
  }, [datasourceStates]);

  useEffect(() => {
    if (!running) {
      return;
    }
    const timer = window.setInterval(() => {
      void refresh();
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [refresh, running]);

  async function runOperation(
    operation: 'installing' | 'updating',
    request: (operationId: string) => Promise<SqlxStatus>,
  ) {
    if (!canStartSqlxOperation(activeOperationIdRef.current)) {
      return;
    }
    const operationId = createSqlxOperationId();
    activeOperationIdRef.current = operationId;
    dispatch({ type: 'START', operation, operationId });
    try {
      applyStatus(await request(operationId));
    } catch (error) {
      dispatch({ type: 'FAILURE', operationId, error: getSqlxErrorMessage(error) });
    }
  }

  async function redetect() {
    if (redetecting || busy || !canStartSqlxOperation(activeOperationIdRef.current)) {
      return;
    }
    setRedetecting(true);
    try {
      await Promise.all([refresh(), delay(REDETECT_MIN_VISIBLE_MS)]);
    } finally {
      setRedetecting(false);
    }
  }

  function install() {
    void runOperation('installing', (operationId) => jcefApi.installSqlx({ operationId }));
  }

  function update() {
    void runOperation('updating', (operationId) => jcefApi.updateSqlx({ operationId }));
  }

  async function checkUpdate() {
    const operationId = createSqlxOperationId();
    dispatch({ type: 'START', operation: 'checking', operationId });
    try {
      applyStatus(await jcefApi.checkSqlxUpdate({ operationId }));
    } catch (error) {
      dispatch({ type: 'FAILURE', operationId, error: getSqlxErrorMessage(error) });
    }
  }

  async function cancelOperation() {
    const operationId = status?.operation?.operationId;
    if (!operationId) {
      return;
    }
    try {
      applyStatus(await jcefApi.cancelSqlxOperation({ operationId }));
    } catch (error) {
      feedback.error(getSqlxErrorMessage(error));
    }
  }

  async function runImport() {
    setImporting(true);
    setImportError(null);
    try {
      setImportSummary(await jcefApi.importSqlxDatasources({ ids: selectedIds }));
      // The rows now report their new state, so the table itself shows what landed in SQLX.
      setSelectedIds([]);
      await loadDatasourceStates(dataSources.map((source) => source.id));
    } catch (error) {
      setImportSummary(null);
      setImportError(getSqlxErrorMessage(error));
    } finally {
      setImporting(false);
    }
  }

  async function chooseBinary() {
    try {
      const selected = await jcefApi.selectFile({ fileTypeList: [] });
      const path = resolveSelectedFilePath(selected);
      if (!path) {
        return;
      }
      applyStatus(await jcefApi.setSqlxBinary({ path }));
    } catch (error) {
      feedback.error(getSqlxErrorMessage(error));
    }
  }

  async function copyText(text: string) {
    await copyToClipboard(text);
    feedback.success(i18n('common.button.copySuccessfully'));
  }

  const operation = status?.operation;
  const failed = operation?.step === 'failed';
  const state = status?.state ?? 'notInstalled';
  const installed = state === 'installed';
  const updateAvailable = status?.latest.status === 'updateAvailable';
  /** Only a state the user has to act on gets a badge; "installed" is visible from the version line. */
  const problemState = state === 'conflict' || state === 'unsupported';
  const showStateLine = problemState || Boolean(status?.version);

  function stateLabel() {
    return state === 'conflict' ? i18n('setting.sqlx.state.conflict') : i18n('setting.sqlx.state.unsupported');
  }

  /** Why a row cannot be imported, or what its badge means; an empty hint shows no tooltip. */
  function datasourceStateHint(source: IConnectionDetails): string {
    const reported = datasourceStates[source.id];
    if (!reported) {
      return '';
    }
    const key = datasourceStateHintKey(reported);
    if (key) {
      return i18n(key);
    }
    return reported.state === 'incomplete' ? skipReasonText(reported.reason) || reported.detail || '' : '';
  }

  /** The status badge of one row: imported, ready, or blocked with the reason on hover. */
  function datasourceStateTag(source: IConnectionDetails) {
    const reported = datasourceStates[source.id];
    const tag = (
      <Tag color={datasourceStateColor(reported?.state)}>{i18n(datasourceStateLabelKey(reported?.state))}</Tag>
    );
    const hint = datasourceStateHint(source);
    return hint ? <Tooltip title={hint}>{tag}</Tooltip> : tag;
  }

  /** The columns the datasource table shows; the type column is what the mapping reacts to. */
  const dataSourceColumns = [
    {
      title: i18n('setting.sqlx.column.name'),
      dataIndex: 'alias',
      key: 'alias',
      render: (_: unknown, source: IConnectionDetails) => source.alias || `#${source.id}`,
    },
    { title: i18n('setting.sqlx.column.type'), dataIndex: 'type', key: 'type' },
    {
      title: i18n('setting.sqlx.column.host'),
      dataIndex: 'host',
      key: 'host',
      render: (_: unknown, source: IConnectionDetails) =>
        source.host ? `${source.host}${source.port ? `:${source.port}` : ''}` : '—',
    },
    {
      title: i18n('setting.sqlx.column.status'),
      key: 'status',
      width: 110,
      render: (_: unknown, source: IConnectionDetails) => datasourceStateTag(source),
    },
  ];

  /** Only the states that need an explanation carry one; installed and not-installed speak for themselves. */
  function describeText() {
    if (state === 'conflict') {
      return i18n('setting.sqlx.conflictDescribe');
    }
    if (state === 'unsupported') {
      return i18n('setting.sqlx.unsupportedDescribe');
    }
    return '';
  }

  function latestText() {
    const latest = status?.latest;
    if (!latest || latest.status === 'unknown') {
      return '';
    }
    if (latest.status === 'updateAvailable' && latest.version) {
      return i18n('setting.sqlx.updateAvailable', latest.version);
    }
    if (latest.status === 'checkFailed') {
      return i18n('setting.sqlx.checkFailed');
    }
    const checkedAt = latest.checkedAt ? new Date(latest.checkedAt).toLocaleString() : '';
    return checkedAt ? i18n('setting.sqlx.upToDate', checkedAt) : i18n('setting.sqlx.upToDateUnknown');
  }

  function operationText() {
    if (!operation) {
      return '';
    }
    if (operation.step === 'downloading') {
      return `${i18n('setting.sqlx.step.downloading')}${operation.percent ? ` ${operation.percent}%` : ''}`;
    }
    if (operation.step === 'verifying') {
      return i18n('setting.sqlx.step.verifying');
    }
    if (operation.step === 'extracting') {
      return i18n('setting.sqlx.step.extracting');
    }
    if (operation.step === 'validating') {
      return i18n('setting.sqlx.step.validating');
    }
    if (operation.step === 'updating') {
      return i18n('setting.sqlx.step.updating');
    }
    return i18n('setting.sqlx.step.installing');
  }

  const manualCommands = manualInstallOptions(platform);
  const pathHint = pathHintCommand(platform);
  const showPathHint = installed && status?.onPath === false;

  return (
    <div className={styles.settingsList}>
      <div className={styles.settingRow} data-setting-search-id="sqlx.install">
        <div className={styles.settingMeta}>
          <Terminal aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.title.sqlxCli')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.text.sqlxCliDescribe')}</div>
          </div>
        </div>
        <div className={styles.settingStack}>
          {showStateLine ? (
            <div className={sqlxStyles.stateLine}>
              {problemState ? (
                <span className={`${sqlxStyles.badge} ${sqlxStyles.badgeWarning}`}>{stateLabel()}</span>
              ) : null}
              {status?.version ? (
                <span className={sqlxStyles.stateLabel}>
                  {i18n('setting.sqlx.versionLabel')} {status.version}
                </span>
              ) : null}
              {installed && status?.source === 'external' ? (
                <span className={sqlxStyles.stateLabel}>{i18n('setting.sqlx.source.external')}</span>
              ) : null}
              {installed && latestText() ? (
                <span className={sqlxStyles.stateLabel}>{latestText()}</span>
              ) : null}
            </div>
          ) : null}
          {describeText() ? <div className={sqlxStyles.describe}>{describeText()}</div> : null}
          {installed && status?.message ? <Alert message={status.message} showIcon type="warning" /> : null}
          {status?.path ? (
            <div className={sqlxStyles.detailLine}>
              <span>{i18n('setting.sqlx.pathLabel')}</span>
              <span className={sqlxStyles.monospace}>{status.path}</span>
            </div>
          ) : null}
          {failed && operation?.message ? (
            <Alert message={i18n('setting.sqlx.operationFailed')} description={operation.message} showIcon type="error" />
          ) : null}
          {!failed && lifecycle.error && isDesktop ? (
            <Alert message={i18n('setting.sqlx.operationFailed')} description={lifecycle.error} showIcon type="error" />
          ) : null}
          {!isDesktop ? (
            <Alert message={i18n('setting.sqlx.desktopOnly')} description={i18n('setting.sqlx.desktopOnlyDescribe')} showIcon type="info" />
          ) : null}
          <div className={sqlxStyles.actions}>
            {installed ? (
              // One button: it offers the update once a newer version is known, otherwise it checks.
              updateAvailable ? (
                <Button
                  disabled={busy || !isDesktop}
                  loading={lifecycle.pending === 'updating' || (running && operation?.kind === 'update')}
                  onClick={update}
                  type="primary"
                >
                  {running && operation?.kind === 'update'
                    ? i18n('setting.sqlx.step.updating')
                    : i18n('setting.sqlx.button.update')}
                </Button>
              ) : (
                <Button
                  disabled={busy || !isDesktop}
                  loading={lifecycle.pending === 'checking'}
                  onClick={checkUpdate}
                >
                  {i18n('setting.sqlx.button.checkUpdate')}
                </Button>
              )
            ) : (
              <>
                <Button
                  disabled={busy || !isDesktop || state !== 'notInstalled'}
                  loading={lifecycle.pending === 'installing' || (running && operation?.kind === 'install')}
                  onClick={install}
                  type="primary"
                >
                  {running && operation?.kind === 'install'
                    ? operationText()
                    : i18n('setting.sqlx.button.install')}
                </Button>
                <Button disabled={busy || !isDesktop} onClick={chooseBinary}>
                  {i18n('setting.sqlx.button.chooseBinary')}
                </Button>
              </>
            )}
            {running && operation?.kind === 'install' && operation.step === 'downloading' ? (
              <Button onClick={cancelOperation}>{i18n('setting.sqlx.button.cancel')}</Button>
            ) : null}
            <Tooltip title={i18n('setting.sqlx.button.redetectHint')}>
              <Button
                disabled={redetecting || busy || !isDesktop}
                icon={<RefreshCw size={14} strokeWidth={1.8} />}
                loading={redetecting}
                onClick={() => void redetect()}
              >
                {i18n('setting.sqlx.button.redetect')}
              </Button>
            </Tooltip>
          </div>
          {showPathHint ? (
            <div className={sqlxStyles.commandBox}>
              <div className={sqlxStyles.commandColumn}>
                <div className={sqlxStyles.hint}>{i18n('setting.sqlx.pathHint')}</div>
                <pre className={sqlxStyles.commandText}>{pathHint}</pre>
              </div>
              <span className={sqlxStyles.copySlot} data-sqlx-copy>
                <Button onClick={() => void copyText(pathHint)} size="small">
                  {i18n('common.button.copy')}
                </Button>
              </span>
            </div>
          ) : null}
          <button
            aria-expanded={showManualInstall}
            className={sqlxStyles.disclosure}
            onClick={() => setShowManualInstall((value) => !value)}
            type="button"
          >
            {showManualInstall ? '▾ ' : '▸ '}
            {i18n('setting.sqlx.scriptInstall')}
          </button>
          {showManualInstall ? (
            <>
              {manualCommands.map((option) => (
                <div className={sqlxStyles.commandBox} key={option.label}>
                  <div className={sqlxStyles.commandColumn}>
                    <div className={sqlxStyles.hint}>{option.label}</div>
                    <pre className={sqlxStyles.commandText}>{option.command}</pre>
                  </div>
                  <span className={sqlxStyles.copySlot} data-sqlx-copy>
                    <Button onClick={() => void copyText(option.command)} size="small">
                      {i18n('common.button.copy')}
                    </Button>
                  </span>
                </div>
              ))}
              <div className={sqlxStyles.hint}>
                <a onClick={() => void jcefApi.openWebPage(SQLX_INSTALL_DOCS_URL)} rel="noreferrer">
                  {i18n('setting.sqlx.manualInstallDocs')}
                </a>
              </div>
            </>
          ) : null}
        </div>
      </div>

      <div className={styles.settingRow} data-setting-search-id="sqlx.datasource">
        <div className={styles.settingMeta}>
          <Database aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.sqlx.section.datasource')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.sqlx.section.datasourceDescribe')}</div>
          </div>
        </div>
        <div className={styles.settingStack}>
          {!installed ? (
            <div className={sqlxStyles.placeholder}>{i18n('setting.sqlx.section.requiresInstall')}</div>
          ) : dataSources.length === 0 ? (
            <div className={sqlxStyles.placeholder}>{i18n('setting.sqlx.section.noDataSources')}</div>
          ) : (
            <>
              <div className={sqlxStyles.dataSourceRow}>
                <Table<IConnectionDetails>
                  className={sqlxStyles.dataSourceTable}
                  columns={dataSourceColumns}
                  dataSource={dataSources}
                  pagination={false}
                  rowKey="id"
                  rowSelection={{
                    onChange: (keys) => setSelectedIds(keys as number[]),
                    selectedRowKeys: selectedIds,
                    getCheckboxProps: (source) => ({
                      disabled: !isDatasourceSelectable(datasourceStates[source.id]?.state),
                    }),
                  }}
                  scroll={{ y: 220 }}
                  size="small"
                />
                <div className={sqlxStyles.dataSourceFooter}>
                  <Button
                    disabled={busy || !isDesktop || selectedIds.length === 0}
                    loading={importing}
                    onClick={runImport}
                    type="primary"
                  >
                    {i18n('setting.sqlx.button.import')}
                  </Button>
                </div>
              </div>
              {importError ? (
                <Alert
                  message={i18n('setting.sqlx.operationFailed')}
                  description={importError}
                  showIcon
                  type="error"
                />
              ) : null}
              {importSummary ? (
                <Alert
                  message={i18n('setting.sqlx.import.done')}
                  description={
                    <>
                      <div>
                        {i18n(
                          'setting.sqlx.import.summary',
                          String(importSummary.added),
                          String(importSummary.updated),
                          String(importSummary.unchanged),
                          String(importSummary.skipped?.length ?? 0),
                        )}
                      </div>
                      {importSummary.skipped?.length ? (
                        <div>
                          {importSummary.skipped
                            .map((item) => `${item.name ?? ''} ${skipReasonText(item.reason)}`.trim())
                            .join(' · ')}
                        </div>
                      ) : null}
                    </>
                  }
                  showIcon
                  type={importSummary.skipped?.length ? 'warning' : 'success'}
                />
              ) : null}
            </>
          )}
        </div>
      </div>

      <div className={styles.settingRow} data-setting-search-id="sqlx.agent">
        <div className={styles.settingMeta}>
          <Sparkles aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.sqlx.section.agent')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.sqlx.section.agentDescribe')}</div>
          </div>
        </div>
        <div className={styles.settingStack}>
          {SQLX_SKILL_COMMANDS.map((option) => (
            <div className={sqlxStyles.commandBox} key={option.label}>
              <div className={sqlxStyles.commandColumn}>
                <div className={sqlxStyles.hint}>{option.label}</div>
                <pre className={sqlxStyles.commandText}>{option.command}</pre>
              </div>
              <span className={sqlxStyles.copySlot} data-sqlx-copy>
                <Button onClick={() => void copyText(option.command)} size="small">
                  {i18n('common.button.copy')}
                </Button>
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
