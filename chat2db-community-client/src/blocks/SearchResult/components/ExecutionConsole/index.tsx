import { memo, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
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
import type {
  SqlExecutionLogContext,
  SqlExecutionLogMessageOutput,
  SqlExecutionLogRecord,
  SqlExecutionLogResultOutput,
} from '@/service/sqlExecutionLog';
import {
  createExecutionConsoleOrderStorageKey,
  getExecutionConsolePreferenceStorage,
  getLatestExecutionEdgeScrollTop,
  orderExecutionLogRecords,
  persistExecutionConsoleOrder,
  readExecutionConsoleOrder,
  type ExecutionConsoleOrder,
} from './executionConsolePreferences';
import { useStyles } from './style';

const ORDER_STORAGE_KEY = createExecutionConsoleOrderStorageKey('community', __RUNTIME_ENV__);

interface IProps {
  records: SqlExecutionLogRecord[];
  onClear: () => void;
  onOpenResult: (resultKey: string) => void;
  isResultAvailable: (resultKey: string) => boolean;
}

export default memo<IProps>(({ records, onClear, onOpenResult, isResultAvailable }) => {
  const {
    styles,
    theme: { appearance },
  } = useStyles();
  const scrollRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const [order, setOrder] = useState<ExecutionConsoleOrder>(() =>
    readExecutionConsoleOrder(getExecutionConsolePreferenceStorage(), ORDER_STORAGE_KEY),
  );
  const [followLatest, setFollowLatest] = useState(true);
  const setCurrentWorkspaceExtend = useWorkspaceStore((state) => state.setCurrentWorkspaceExtend);
  const orderedRecords = useMemo(() => orderExecutionLogRecords(records, order), [records, order]);

  const alignToLatest = useCallback(() => {
    const container = scrollRef.current;
    if (container) {
      container.scrollTop = getLatestExecutionEdgeScrollTop(container.scrollHeight, order);
    }
  }, [order]);

  useEffect(() => {
    if (!followLatest) return;
    const frame = window.requestAnimationFrame(alignToLatest);
    return () => window.cancelAnimationFrame(frame);
  }, [orderedRecords, followLatest, alignToLatest]);

  useEffect(() => {
    const container = scrollRef.current;
    const content = contentRef.current;
    if (!container || !content || typeof ResizeObserver === 'undefined') return;

    let frame: number | undefined;
    const observer = new ResizeObserver(() => {
      if (!followLatest) return;
      if (frame !== undefined) window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(alignToLatest);
    });
    observer.observe(container);
    observer.observe(content);

    return () => {
      observer.disconnect();
      if (frame !== undefined) window.cancelAnimationFrame(frame);
    };
  }, [followLatest, alignToLatest]);

  const plainText = useMemo(() => buildPlainText(orderedRecords), [orderedRecords]);

  const handleCopy = async () => {
    await copyToClipboard(plainText);
    staticMessage.success(i18n('common.button.copySuccessfully'));
  };

  const handleToggleFollowLatest = () => {
    if (followLatest) {
      setFollowLatest(false);
      return;
    }
    setFollowLatest(true);
    alignToLatest();
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
            { type: 'divider' },
            { key: 'clear', icon: <Trash2 size={14} />, label: i18n('common.button.clearConsole'), danger: true },
          ],
          onClick: handleContextMenuClick,
        }}
        trigger={['contextMenu']}
      >
        <div className={styles.scrollArea} ref={scrollRef}>
          <div className={styles.scrollContent} ref={contentRef}>
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
                  <div className={styles.line}>
                    <TimeCell value={record.startedAtEpochMs} prominent />
                    <div className={styles.sqlContent}>
                      <span className={styles.prompt}>
                        {record.context.schemaName || record.context.databaseName || 'SQL'}&gt;
                      </span>
                      <SQLPreview className={styles.sql} sql={record.sql} source="execution-console" />
                    </div>
                  </div>
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
                    <ConsoleLine
                      className={styles.runningLine}
                      timestamp={record.startedAtEpochMs}
                      content={
                        <span className={styles.runningContent}>
                          <span className={styles.runningDot} />
                          {i18n('common.text.currentExecution')}
                        </span>
                      }
                    />
                  )}
                  {record.status === 'cancelled' && (
                    <ConsoleLine
                      className={styles.cancelledLine}
                      timestamp={record.finishedAtEpochMs || record.startedAtEpochMs}
                      content={i18n('common.text.executionCancelled')}
                    />
                  )}
                  {record.status === 'success' && record.outputs.length === 0 && (
                    <ConsoleLine
                      className={styles.successLine}
                      timestamp={record.finishedAtEpochMs || record.startedAtEpochMs}
                      content={`${i18n('common.text.executionCompleted')} · ${formatMilliseconds(record.durationMs)}`}
                    />
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </Dropdown>
    </div>
  );
});

function MessageLine({
  output,
  record,
  onAIDiagnose,
}: {
  output: SqlExecutionLogMessageOutput;
  record: SqlExecutionLogRecord;
  onAIDiagnose: (record: SqlExecutionLogRecord, message: string) => void;
}) {
  const { styles, cx } = useStyles();
  return (
    <div className={styles.line}>
      <TimeCell value={output.occurredAtEpochMs} prominent />
      <div className={cx(styles.message, styles[`message${output.level}`])}>
        <span className={cx(styles.level, output.level === 'INFO' && styles.infoLevel)}>{output.level}</span>
        <span className={cx(styles.messageText, output.level === 'INFO' && styles.messageINFOText)}>
          {output.message}
        </span>
        {output.level === 'ERROR' && (
          <Button
            type="link"
            size="small"
            className={styles.inlineAction}
            icon={<Sparkles size={13} />}
            onClick={() => onAIDiagnose(record, output.message)}
          >
            {i18n('common.text.aiDiagnose')}
          </Button>
        )}
      </div>
    </div>
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
    <div className={styles.line}>
      <TimeCell value={output.occurredAtEpochMs} prominent={!output.success} />
      <div className={cx(styles.resultLine, !output.success && styles.resultError)}>
        {available ? (
          <button className={styles.resultLink} onClick={() => onOpenResult(output.resultKey!)}>
            {summary}
          </button>
        ) : (
          <span>{summary}</span>
        )}
        {!!output.resultKey && !available && <span className={styles.released}> · {i18n('common.text.resultReleased')}</span>}
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
      </div>
    </div>
  );
}

function ConsoleLine({
  timestamp,
  content,
  className,
}: {
  timestamp: number;
  content: ReactNode;
  className?: string;
}) {
  const { styles, cx } = useStyles();
  return (
    <div className={cx(styles.line, className)}>
      <TimeCell value={timestamp} />
      <div>{content}</div>
    </div>
  );
}

function TimeCell({ value, prominent = false }: { value: number; prominent?: boolean }) {
  const { styles, cx } = useStyles();
  return (
    <time className={cx(styles.timestamp, prominent && styles.prominentTimestamp)}>[{formatTimestamp(value)}]</time>
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

function formatTimestamp(value: number) {
  const date = new Date(value);
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
    date.getMinutes(),
  )}:${pad(date.getSeconds())}`;
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
      lines.push(`[${formatTimestamp(record.startedAtEpochMs)}] ${record.context.schemaName || record.context.databaseName || 'SQL'}> ${record.sql}`);
      record.outputs.forEach((output) => {
        const text = output.kind === 'message' ? `${output.level} ${output.message}` : `${resultSummary(output)}${formatMetrics(output)}`;
        lines.push(`[${formatTimestamp(output.occurredAtEpochMs)}] ${text}`);
      });
      if (record.status === 'cancelled') {
        lines.push(`[${formatTimestamp(record.finishedAtEpochMs || record.startedAtEpochMs)}] ${i18n('common.text.executionCancelled')}`);
      }
      return lines;
    })
    .join('\n');
}
