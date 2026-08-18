import { memo, useMemo, useState } from 'react';
import { Button, Dropdown, type MenuProps } from 'antd';
import { ArrowDownToLine, ArrowDownUp, ArrowUpToLine, Check, Copy, Sparkles, Trash2 } from 'lucide-react';
import { IconfontSvg, staticMessage } from '@chat2db/ui';
import i18n from '@/i18n';
import { copyToClipboard } from '@/utils/copy';
import SQLPreview from '@/components/SQLPreview';
import { getDatabaseInfo } from '@/constants';
import { useAIStore } from '@/store/ai';
import { useGlobalStore } from '@/store/global';
import { useWorkspaceStore } from '@/store/workspace';
import { QuestionType } from '@/constants/chat';
import {
  ConsoleOutputEmpty,
  ConsoleOutputLine,
  ConsoleOutputMessageLine,
  ConsoleOutputViewport,
  formatConsoleOutputTimestamp,
} from '@/components/ConsoleOutput';
import type {
  SqlExecutionLogContext,
  SqlExecutionLogMessageOutput,
  SqlExecutionLogRecord,
  SqlExecutionLogResultOutput,
} from '@/service/sqlExecutionLog';
import {
  createExecutionConsoleOrderStorageKey,
  getExecutionConsolePreferenceStorage,
  orderExecutionLogRecords,
  persistExecutionConsoleOrder,
  readExecutionConsoleOrder,
  type ExecutionConsoleOrder,
} from './executionConsolePreferences';
import { useStyles } from './style';

const ORDER_STORAGE_KEY = createExecutionConsoleOrderStorageKey('community', __RUNTIME_ENV__);

interface IProps {
  records: SqlExecutionLogRecord[];
  keepHistory: boolean;
  onClear: () => void;
  onKeepHistoryChange: (keepHistory: boolean) => void;
  onOpenResult: (resultKey: string) => void;
  isResultAvailable: (resultKey: string) => boolean;
}

export default memo<IProps>(
  ({ records, keepHistory, onClear, onKeepHistoryChange, onOpenResult, isResultAvailable }) => {
    const {
      styles,
      theme: { appearance },
    } = useStyles();
    const [order, setOrder] = useState<ExecutionConsoleOrder>(() =>
      readExecutionConsoleOrder(getExecutionConsolePreferenceStorage(), ORDER_STORAGE_KEY),
    );
    const [followLatest, setFollowLatest] = useState(true);
    const setCurrentWorkspaceExtend = useWorkspaceStore((state) => state.setCurrentWorkspaceExtend);
    const orderedRecords = useMemo(() => orderExecutionLogRecords(records, order), [records, order]);

    const plainText = useMemo(() => buildPlainText(orderedRecords), [orderedRecords]);

    const handleCopy = async () => {
      await copyToClipboard(plainText);
      staticMessage.success(i18n('common.button.copySuccessfully'));
    };

    const handleToggleFollowLatest = () => {
      setFollowLatest((current) => !current);
    };

    const handleOrderChange = (nextOrder: ExecutionConsoleOrder) => {
      setOrder(nextOrder);
      persistExecutionConsoleOrder(getExecutionConsolePreferenceStorage(), ORDER_STORAGE_KEY, nextOrder);
    };

    const handleContextMenuClick: MenuProps['onClick'] = ({ key }) => {
      if (key === 'copy') {
        void handleCopy();
      } else if (key === 'clear') {
        onClear();
      } else if (key === 'follow') {
        handleToggleFollowLatest();
      } else if (key === 'keep-history') {
        onKeepHistoryChange(!keepHistory);
      } else if (key === 'toggle-order') {
        handleOrderChange(order === 'oldest-first' ? 'newest-first' : 'oldest-first');
      }
    };

    const handleAIDiagnose = (record: SqlExecutionLogRecord, errorMessage: string) => {
      const page = useGlobalStore.getState().mainPageActiveTab as 'workspace' | 'dashboard' | 'chat' | 'stream';
      setCurrentWorkspaceExtend(null);
      useAIStore.getState().setCascaderData(page, record.context);
      useAIStore.getState().setShowPanel(true);
      window.setTimeout(() => {
        window.dispatchEvent(
          new CustomEvent('stream:prefillMessage', {
            detail: {
              input: i18n('ai.sqlDebug.prefill', record.sql || '', errorMessage),
              questionType: QuestionType.SQL_DEBUG,
            },
          }),
        );
      }, 100);
    };

    return (
      <div className={styles.console}>
        <Dropdown
          menu={{
            selectable: true,
            selectedKeys: [...(followLatest ? ['follow'] : []), ...(keepHistory ? ['keep-history'] : [])],
            items: [
              { key: 'copy', icon: <Copy size={14} />, label: i18n('common.button.copyConsole') },
              { type: 'divider' },
              {
                key: 'toggle-order',
                icon: <ArrowDownUp size={14} />,
                label: `${i18n('common.text.order')}: ${i18n(
                  order === 'oldest-first' ? 'common.text.oldestFirst' : 'common.text.newestFirst',
                )}`,
              },
              {
                key: 'follow',
                icon: followLatest ? (
                  <Check size={14} />
                ) : order === 'newest-first' ? (
                  <ArrowUpToLine size={14} />
                ) : (
                  <ArrowDownToLine size={14} />
                ),
                label: i18n('common.button.followConsole'),
              },
              {
                key: 'keep-history',
                icon: <Check opacity={keepHistory ? 1 : 0} size={14} />,
                label: i18n('common.button.keepHistoryOutput'),
              },
              { type: 'divider' },
              { key: 'clear', icon: <Trash2 size={14} />, label: i18n('common.button.clearConsole'), danger: true },
            ],
            onClick: handleContextMenuClick,
          }}
          trigger={['contextMenu']}
        >
          <ConsoleOutputViewport
            contentVersion={orderedRecords}
            followLatest={followLatest}
            latestAtStart={order === 'newest-first'}
          >
            {orderedRecords.map((record, recordIndex) => {
              const showContext =
                recordIndex === 0 || contextKey(orderedRecords[recordIndex - 1].context) !== contextKey(record.context);
              const databaseInfo = getDatabaseInfo(record.context.databaseType);
              return (
                <div className={styles.record} key={record.id}>
                  {showContext && (
                    <div className={styles.contextLine}>
                      <span className={styles.contextRule} />
                      <span className={styles.contextContent}>
                        <IconfontSvg
                          className={styles.databaseIcon}
                          size={14}
                          existDark={databaseInfo?.iconExistDark}
                          appearance={appearance}
                          code={databaseInfo?.icon || 'icon-chat-database'}
                        />
                        <span className={styles.contextText}>{formatContext(record.context)}</span>
                      </span>
                      <span className={styles.contextRule} />
                    </div>
                  )}
                  <ConsoleOutputLine timestamp={record.startedAtEpochMs} timestampProminent>
                    <div className={styles.sqlContent}>
                      <span className={styles.prompt}>
                        {record.context.schemaName || record.context.databaseName || 'SQL'}&gt;
                      </span>
                      <SQLPreview className={styles.sql} sql={record.sql} source="execution-console" />
                    </div>
                  </ConsoleOutputLine>
                  {record.outputs.map((output) =>
                    output.kind === 'message' ? (
                      <MessageLine key={output.id} output={output} record={record} onAIDiagnose={handleAIDiagnose} />
                    ) : (
                      <ResultLine
                        key={output.id}
                        output={output}
                        record={record}
                        isResultAvailable={isResultAvailable}
                        onOpenResult={onOpenResult}
                        onAIDiagnose={handleAIDiagnose}
                      />
                    ),
                  )}
                  {record.status === 'running' && (
                    <ConsoleOutputLine className={styles.runningLine} timestamp={record.startedAtEpochMs}>
                      <span className={styles.runningContent}>
                        <span className={styles.runningDot} />
                        {i18n('common.text.currentExecution')}
                      </span>
                    </ConsoleOutputLine>
                  )}
                  {record.status === 'cancelled' && (
                    <ConsoleOutputLine
                      className={styles.cancelledLine}
                      timestamp={record.finishedAtEpochMs || record.startedAtEpochMs}
                    >
                      {i18n('common.text.executionCancelled')}
                    </ConsoleOutputLine>
                  )}
                  {record.status === 'success' && record.outputs.length === 0 && (
                    <ConsoleOutputLine
                      className={styles.successLine}
                      timestamp={record.finishedAtEpochMs || record.startedAtEpochMs}
                    >
                      {`${i18n('common.text.executionCompleted')} · ${formatMilliseconds(record.durationMs)}`}
                    </ConsoleOutputLine>
                  )}
                </div>
              );
            })}
            {!orderedRecords.length && <ConsoleOutputEmpty>{i18n('common.text.noData')}</ConsoleOutputEmpty>}
          </ConsoleOutputViewport>
        </Dropdown>
      </div>
    );
  },
);

function MessageLine({
  output,
  record,
  onAIDiagnose,
}: {
  output: SqlExecutionLogMessageOutput;
  record: SqlExecutionLogRecord;
  onAIDiagnose: (record: SqlExecutionLogRecord, message: string) => void;
}) {
  const { styles } = useStyles();
  return (
    <ConsoleOutputMessageLine
      timestamp={output.occurredAtEpochMs}
      level={output.level}
      message={output.message}
      action={
        output.level === 'ERROR' ? (
          <Button
            type="link"
            size="small"
            className={styles.inlineAction}
            icon={<Sparkles size={13} />}
            onClick={() => onAIDiagnose(record, output.message)}
          >
            {i18n('common.text.aiDiagnose')}
          </Button>
        ) : undefined
      }
    />
  );
}

function ResultLine({
  output,
  record,
  isResultAvailable,
  onOpenResult,
  onAIDiagnose,
}: {
  output: SqlExecutionLogResultOutput;
  record: SqlExecutionLogRecord;
  isResultAvailable: (resultKey: string) => boolean;
  onOpenResult: (resultKey: string) => void;
  onAIDiagnose: (record: SqlExecutionLogRecord, message: string) => void;
}) {
  const { styles, cx } = useStyles();
  const available = !!output.resultKey && isResultAvailable(output.resultKey);
  const summary = resultSummary(output);
  return (
    <ConsoleOutputLine
      timestamp={output.occurredAtEpochMs}
      timestampProminent={!output.success}
      contentClassName={cx(styles.resultLine, !output.success && styles.resultError)}
    >
      {available ? (
        <button className={styles.resultLink} onClick={() => onOpenResult(output.resultKey!)}>
          {summary}
        </button>
      ) : (
        <span>{summary}</span>
      )}
      {!!output.resultKey && !available && (
        <span className={styles.released}> · {i18n('common.text.resultReleased')}</span>
      )}
      {output.success && <span className={styles.metrics}>{formatMetrics(output)}</span>}
      {!output.success && output.message && (
        <Button
          type="link"
          size="small"
          className={styles.inlineAction}
          icon={<Sparkles size={13} />}
          onClick={() => onAIDiagnose(record, output.message!)}
        >
          {i18n('common.text.aiDiagnose')}
        </Button>
      )}
    </ConsoleOutputLine>
  );
}

function resultSummary(output: SqlExecutionLogResultOutput) {
  if (!output.success) return output.message || i18n('common.text.failure');
  if (typeof output.updateCount === 'number') return i18n('common.text.affectedRows', output.updateCount);
  if (typeof output.rowCount === 'number') return i18n('common.text.rowsReturned', output.rowCount);
  return i18n('common.text.executionCompleted');
}

function formatMetrics(output: SqlExecutionLogResultOutput) {
  const metrics = output.executionMetrics;
  const details: string[] = [];
  if (typeof metrics?.executeDurationMs === 'number') {
    details.push(i18n('common.text.executeDuration', metrics.executeDurationMs));
  }
  if (typeof metrics?.fetchDurationMs === 'number') {
    details.push(i18n('common.text.fetchDuration', metrics.fetchDurationMs));
  }
  const total = formatMilliseconds(metrics?.totalDurationMs ?? output.durationMs);
  return details.length ? ` · ${total} (${details.join(' · ')})` : ` · ${total}`;
}

function formatMilliseconds(value?: number) {
  return `${Math.max(0, value || 0)} ms`;
}

function formatContext(context: SqlExecutionLogContext) {
  const source = context.dataSourceName || (context.dataSourceId ? `#${context.dataSourceId}` : 'SQL');
  return [source, context.databaseName, context.schemaName].filter(Boolean).join(' / ');
}

function contextKey(context: SqlExecutionLogContext) {
  return [
    context.dataSourceId,
    context.dataSourceName,
    context.databaseType,
    context.databaseName,
    context.schemaName,
  ].join('|');
}

function buildPlainText(records: SqlExecutionLogRecord[]) {
  return records
    .flatMap((record, index) => {
      const lines: string[] = [];
      if (index === 0 || contextKey(records[index - 1].context) !== contextKey(record.context)) {
        lines.push(`--- ${formatContext(record.context)} ---`);
      }
      lines.push(
        `[${formatConsoleOutputTimestamp(record.startedAtEpochMs)}] ${
          record.context.schemaName || record.context.databaseName || 'SQL'
        }> ${record.sql}`,
      );
      record.outputs.forEach((output) => {
        const text =
          output.kind === 'message'
            ? `${output.level} ${output.message}`
            : `${resultSummary(output)}${formatMetrics(output)}`;
        lines.push(`[${formatConsoleOutputTimestamp(output.occurredAtEpochMs)}] ${text}`);
      });
      if (record.status === 'cancelled') {
        lines.push(
          `[${formatConsoleOutputTimestamp(record.finishedAtEpochMs || record.startedAtEpochMs)}] ${i18n(
            'common.text.executionCancelled',
          )}`,
        );
      }
      return lines;
    })
    .join('\n');
}
