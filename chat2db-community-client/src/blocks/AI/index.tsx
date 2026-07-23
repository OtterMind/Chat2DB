import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Dropdown, Flex, Modal } from 'antd';
import feedback from '@/utils/feedback';
import {
  CopyOutlined,
  CheckOutlined,
  PushpinOutlined,
  PlusOutlined,
  HistoryOutlined,
  CloseOutlined,
  DeleteOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import ChartCardBox from '@/blocks/BI/ChartCardBox';
import { IChartItem } from '@/typings/dashboard';
import { ChartSchema } from '@/blocks/BI/Chart/typings';
import { ChartType, LineType, OrderByType, OrderByRule } from '@/blocks/BI/Chart/constants';
import { TableDataType } from '@/constants/table';
import useSSERequest, { SSERequestStatus } from '@/hooks/useSSERequest';
import AIChatInput, { ChatInputPropsRef, SendParams } from './components/AIChatInput';
import aiStreamService, { IChatMessage, IChatSession, IModelOptionItem } from '@/service/aiStream';
import { IChatAttachment } from '@/service/aiAttachment';
import { useAIStore } from '@/store/ai';
import { useTreeStore } from '@/store/tree';
import { useGlobalStore } from '@/store/global';
import { useWorkspaceStore } from '@/store/workspace';
import { WorkspaceTabType } from '@/constants/workspace';
import { OperationColumn } from '@/constants/tree';
import { compatibleDataBaseName } from '@/utils/database';
import { DatabaseTypeCode } from '@/constants';
import SQLPreview from '@/components/SQLPreview';
import { useStyles } from './style';
import i18n from '@/i18n';
import { keyboardKey } from '@/utils';
import { cx } from 'antd-style';
import AIModelConfigModal from './components/AIModelConfigModal';
import { listAvailableModelOptions, resolveModelRequestPayload } from '@/service/aiModelConfig';
import { isDesktop } from '@/utils/env';

/** detects unclosed text in flowing text ```chart block, return chart and whether there are any unfinished diagrams */
function splitIncompleteChartBlock(text: string): { textBeforeChart: string; hasIncompleteChart: boolean } {
  const chartOpenMarker = '```chart';
  const lastIdx = text.lastIndexOf(chartOpenMarker);
  if (lastIdx === -1) {
    return { textBeforeChart: text, hasIncompleteChart: false };
  }
  // is in ```chart Then look for closed ones. ```
  const afterOpen = text.substring(lastIdx + chartOpenMarker.length);
  if (/\n```/.test(afterOpen)) {
    // The block is closed and can be rendered normally.
    return { textBeforeChart: text, hasIncompleteChart: false };
  }
  // The block is incomplete, so truncate before the chart block.
  return { textBeforeChart: text.substring(0, lastIdx), hasIncompleteChart: true };
}

/** Convert chart JSON returned by AI into the IChartItem required by ChartCardBox. */
function buildChartDetail(chartJson: Record<string, any>): IChartItem {
  const { data = [], ...rest } = chartJson;

  const chartSchema: ChartSchema = {
    chartType: rest.chartType ?? ChartType.Column,
    xField: rest.xField ?? null,
    yField: rest.yField ?? null,
    angleField: rest.angleField ?? null,
    valueField: rest.valueField ?? null,
    colorField: rest.colorField ?? null,
    textField: rest.textField ?? null,
    title: rest.title,
    summary: rest.summary,
    themeColorCode: rest.themeColorCode ?? 'v1-baby-blue',
    lineType: rest.lineType ?? LineType.Straight,
    orderByType: rest.orderByType ?? OrderByType.DEFAULT,
    orderByRule: rest.orderByRule ?? OrderByRule.DESC,
    comboYAxisData: rest.comboYAxisData ?? undefined,
    chartOptionCheckbox: rest.chartOptionCheckbox ?? [
      'showLegend',
      'showLabel',
      'showAxisLine',
      'showSplitLine',
      'showSymbol',
    ],
    data,
  };

  // Build metadata from the data array for the Chart component.
  const keys: string[] = data.length > 0 ? Object.keys(data[0]) : [];
  const sampleRow = data[0] ?? {};
  const headerList = keys.map((key) => ({
    name: key,
    dataType: typeof sampleRow[key] === 'number' ? TableDataType.NUMERIC : TableDataType.STRING,
  }));
  const dataList = data.map((row: Record<string, any>) => keys.map((k) => row[k]));

  return {
    chartSchema,
    metaData: {
      headerList,
      dataList,
      description: '',
      sql: '',
      originalSql: '',
      success: true,
      duration: 0,
      sqlType: 'SELECT' as any,
      refreshTargets: [],
      pageNo: 1,
      pageSize: data.length,
      fuzzyTotal: String(data.length),
      hasNextPage: false,
    } as any,
  };
}

/** Convert [table::tableName] to inline code so ReactMarkdown can recognize it. */
function preprocessTableRefs(content: string) {
  return content.replace(/\[table::([^\]]+)\]/g, '`table::$1`');
}

/** Normalize nonstandard Markdown so missing newlines do not break rendered structures. */
function normalizeAiMarkdown(content: string) {
  const repaired = content
    .replace(/\r\n/g, '\n')
    .replace(/([^\n])```chart/g, '$1\n\n```chart')
    .replace(/```chart\s*(\{[\s\S]*?\})\s*```/g, '```chart\n$1\n```');
  // Segment by code fence, including fences that have not been closed during streaming.
  // Apply repair rules only to text outside the fence.
  // avoids accidentally changing ##, ---, -- comments, etc. in the code
  return repaired
    .split(/(```[\s\S]*?(?:```|$))/)
    .map((segment, index) => (index % 2 === 1 ? segment : normalizeTextSegment(segment)))
    .join('');
}

/** applies format repair rules to normal text segments outside code fences */
function normalizeTextSegment(text: string) {
  return text
    // Repair headings stuck to the previous paragraph only for "text### title" and "text###1." forms.
    // This avoids damaging inline ## fragments such as URL anchors.
    .replace(/([^\s#\n])(#{2,6}) (?=\S)/g, '$1\n\n$2 ')
    .replace(/([^\s#\n])(#{2,6})(?=\d)/g, '$1\n\n$2 ')
    // The title at the beginning of the line is missing a space (###1. → ### 1.)
    .replace(/(^|\n)(#{2,6})(?=[^#\s])/g, '$1$2 ')
    // Move a separator stuck to a sentence onto its own line (text---\n).
    // It must end the line and cannot be preceded by |, :, or whitespace.
    // Avoid accidentally damaging the table separator line | --- | and alignment syntax: ---
    .replace(/([^\n|:\s-])(-{3,})(?=\n|$)/g, '$1\n\n$2')
    .replace(/((?:-\s*\d{4}-\d{2}-\d{2}\s*[:：]\s*[-+]?\d+(?:\.\d+)?\s*)+)/g, (segment) =>
      Array.from(segment.matchAll(/-\s*(\d{4}-\d{2}-\d{2})\s*[:：]\s*([-+]?\d+(?:\.\d+)?)/g))
        .map(([, date, value]) => `- ${date}: ${value}`)
        .join('\n') + '\n',
    )
    // Add a missing space after a leading hyphen, excluding double hyphens and dividers.
    .replace(/(^|\n)-(?=[^\s-])/g, '$1- ')
    // Split list items attached to the previous sentence into standalone items.
    // Exclude a preceding hyphen to preserve dividers and SQL double-hyphen comments.
    .replace(/([^\n\s-])-\s+(?=[*`A-Za-z0-9一-鿿])/g, '$1\n- ')
    .replace(/([0-9：:])-(?=[A-Za-z一-鿿])/g, '$1\n- ')
    .replace(/([^\n])\s+-\s*(\d{4}-\d{2}-\d{2}\s*[：:])/g, '$1\n- $2')
    .replace(/(^|\n)-(\d{4}-\d{2}-\d{2})/g, '$1- $2');
}

/** Table name click callback Context, used by MarkdownCodeBlock */
const TableClickContext = React.createContext<((tableName: string) => void) | null>(null);

/** SQL nailed to the Context of the Console */
const PinSqlContext = React.createContext<((sql: string) => void) | null>(null);

/**
 * Code block component: language=chart renders a chart, table:: renders a clickable table name,
 * and all other languages render code.
 */
function MarkdownCodeBlock({ className, children }: { className?: string; children?: React.ReactNode }) {
  const { styles } = useStyles();
  const [copied, setCopied] = useState(false);
  const onTableClick = React.useContext(TableClickContext);
  const onPinSql = React.useContext(PinSqlContext);
  const match = /language-(\w+)/.exec(className || '');

  const submitEditorChartCallback = (data: any) => {
    console.log('Chart updated:', data);
  };

  if (!match) {
    // checks whether it is a clickable table name prefixed by table::
    const text = String(children).replace(/\n$/, '');
    const tableMatch = text.match(/^table::(.+)$/);
    if (tableMatch) {
      const tName = tableMatch[1];
      return (
        <span className={styles.clickableTable} onClick={() => onTableClick?.(tName)}>
          <svg
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <rect x="3" y="3" width="18" height="18" rx="2" />
            <line x1="3" y1="9" x2="21" y2="9" />
            <line x1="3" y1="15" x2="21" y2="15" />
            <line x1="9" y1="3" x2="9" y2="21" />
          </svg>
          {tName}
        </span>
      );
    }
    // Normal inline code
    return <code className={styles.inlineCode}>{children}</code>;
  }

  const lang = match[1];
  const code = String(children).replace(/\n$/, '');

  // ── Chart rendering ──
  if (lang === 'chart') {
    try {
      const chartJson = JSON.parse(code);
      console.log('[AI chart render json]', chartJson);
      const chartDetail = buildChartDetail(chartJson);
      return (
        <div className={styles.chartCard}>
          <ChartCardBox
            chartDetail={chartDetail}
            showDing
            className={styles.chartCardInner}
            submitEditorChartCallback={submitEditorChartCallback}
          />
        </div>
      );
    } catch {
      // JSON degrades to code block display when parsing fails
    }
  }

  // ── Ordinary code block ──
  const isSql = /^sql$/i.test(lang);

  const handleCopy = () => {
    navigator.clipboard.writeText(code).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handlePin = () => {
    onPinSql?.(code);
  };

  if (isSql) {
    return (
      <SQLPreview
        className={styles.leftAlignedSqlPreview}
        sql={code}
        language={lang}
        source="ai-markdown-sql-code-block"
        type="block"
        renderAddons={
          onPinSql
            ? () => (
                <button className={styles.codeBlockCopyBtn} onClick={handlePin}>
                  <PushpinOutlined />
                  <span>{i18n('stream.codeBlock.pin')}</span>
                </button>
              )
            : undefined
        }
      />
    );
  }

  return (
    <div className={styles.codeBlockWrap}>
      <div className={styles.codeBlockHeader}>
        <span className={styles.codeBlockLang}>{lang}</span>
        <div className={styles.codeBlockActions}>
          {isSql && onPinSql && (
            <button className={styles.codeBlockCopyBtn} onClick={handlePin}>
              <PushpinOutlined />
              <span>{i18n('stream.codeBlock.pin')}</span>
            </button>
          )}
          <button className={styles.codeBlockCopyBtn} onClick={handleCopy}>
            {copied ? <CheckOutlined /> : <CopyOutlined />}
            <span>{copied ? i18n('stream.codeBlock.copied') : i18n('stream.codeBlock.copy')}</span>
          </button>
        </div>
      </div>
      <pre className={styles.codeBlockPre}>
        <code>{code}</code>
      </pre>
    </div>
  );
}

/** is sent each time AI The maximum number of historical rounds carried (one round = (one question and one answer) */
const MAX_HISTORY_ROUNDS = 5;
const SCROLL_BOTTOM_THRESHOLD = 24;
const INITIAL_VIEWPORT_ANIMATION_MS = 260;
const PROGRAMMATIC_SCROLL_LOCK_MS = 120;
const MESSAGE_TOP_ALIGNMENT_GAP = 20;
const COLLAPSED_THOUGHT_PREVIEW_MAX_LENGTH = 48;

type ChatRole = 'user' | 'assistant';

interface IChatItem {
  id: string;
  role: ChatRole;
  content: string;
  attachments?: IChatAttachment[];
  traceEntries?: ITraceEntry[];
}

interface IChatRound {
  key: string;
  user?: IChatItem;
  assistant?: IChatItem;
}

interface IStreamChunk {
  type?: 'reasoning' | 'tool_call' | 'tool_result' | 'answer' | 'done' | 'error' | 'session';
  messageType?: 'reasoning' | 'tool_call' | 'tool_result' | 'answer' | 'done' | 'error' | 'session';
  content?: string;
  name?: string;
  arguments?: string;
  sessionId?: string;
  ts?: number;
  id?: string;
}

interface IInProgressSessionSnapshot {
  sessionId: string;
  title: string;
  messages: IChatItem[];
  streamingText: string;
  traceEntries: ITraceEntry[];
  currentRoundUserMessageId: string | null;
}

interface ITraceEntry {
  type: 'reasoning' | 'tool_call' | 'tool_result' | 'error';
  content?: string;
  name?: string;
  arguments?: string;
  ts?: number;
  id?: string;
}

export interface ITableClickContext {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  databaseType?: DatabaseTypeCode;
  dataSourceName?: string;
}

interface IAIProps {
  variant?: 'page' | 'panel';
  onTableClick?: (tableName: string, context: ITableClickContext) => void;
  onPinSql?: (sql: string, context: ITableClickContext) => void;
  onSessionChange?: () => void;
}

function parseTraceEntries(raw?: string): ITraceEntry[] {
  if (!raw) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .map((item) => normalizeTraceEntry(item))
      .filter((item): item is ITraceEntry => !!item);
  } catch {
    return raw.trim()
      ? [
          {
            type: 'reasoning',
            content: raw,
          },
        ]
      : [];
  }
}

function normalizeTraceEntry(input: Partial<ITraceEntry> | null | undefined): ITraceEntry | null {
  if (!input) {
    return null;
  }
  const rawType = input.type;
  if (rawType !== 'reasoning' && rawType !== 'tool_call' && rawType !== 'tool_result' && rawType !== 'error') {
    return null;
  }
  return {
    type: rawType,
    content: input.content,
    name: input.name,
    arguments: input.arguments,
    ts: input.ts,
    id: input.id,
  };
}

function mergeTraceEntries(prev: ITraceEntry[], nextEntry: ITraceEntry): ITraceEntry[] {
  if (!prev.length) {
    return [nextEntry];
  }
  const lastEntry = prev[prev.length - 1];
  if (lastEntry.type === 'reasoning' && nextEntry.type === 'reasoning') {
    return [
      ...prev.slice(0, -1),
      {
        ...lastEntry,
        content: `${lastEntry.content || ''}${nextEntry.content || ''}`,
        ts: nextEntry.ts || lastEntry.ts,
      },
    ];
  }
  return [...prev, nextEntry];
}

function getLatestMeaningfulLine(text?: string) {
  if (!text) {
    return '';
  }
  const lines = text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  return lines[lines.length - 1] || text.trim();
}

function getTracePreview(entry?: ITraceEntry, fallbackText?: string) {
  if (!entry) {
    return fallbackText || '';
  }
  if (entry.type === 'reasoning') {
    return getLatestMeaningfulLine(entry.content);
  }
  if (entry.type === 'tool_call') {
    return `${i18n('stream.trace.toolCall')}: ${entry.name || i18n('stream.trace.unknownTool')}`;
  }
  if (entry.type === 'tool_result') {
    const suffix = getLatestMeaningfulLine(entry.content);
    const title = entry.name || i18n('stream.trace.defaultToolResult');
    return `${title}${suffix ? `: ${suffix}` : ''}`;
  }
  return getLatestMeaningfulLine(entry.content) || i18n('stream.trace.unknownError');
}

function truncateCollapsedThoughtPreview(text?: string, maxLength = COLLAPSED_THOUGHT_PREVIEW_MAX_LENGTH) {
  if (!text) {
    return '';
  }
  const normalized = text.replace(/\s+/g, ' ').trim();
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return `${normalized.slice(0, Math.max(0, maxLength - 1)).trimEnd()}…`;
}

function isLikelySameSessionFromPrefix(serverMessages: IChatItem[], snapshotMessages: IChatItem[]) {
  if (!serverMessages.length || !snapshotMessages.length) {
    return false;
  }
  if (serverMessages.length > snapshotMessages.length) {
    return false;
  }
  for (let i = 0; i < serverMessages.length; i += 1) {
    const serverItem = serverMessages[i];
    const snapshotItem = snapshotMessages[i];
    if (serverItem.role !== snapshotItem.role) {
      return false;
    }
    if ((serverItem.content || '').trim() !== (snapshotItem.content || '').trim()) {
      return false;
    }
  }
  return true;
}

export default function AI({ variant = 'page', onTableClick, onPinSql, onSessionChange }: IAIProps) {
  const isPanel = variant === 'panel';
  const { styles } = useStyles();
  const [modelOptions, setModelOptions] = useState<Array<{ label: string; value: string; isDefault?: boolean }>>([]);
  const [modelOptionMap, setModelOptionMap] = useState<Record<string, IModelOptionItem>>({});
  const [messages, setMessages] = useState<IChatItem[]>([]);
  const [streamingText, setStreamingText] = useState('');
  const [streamTraceEntries, setStreamTraceEntries] = useState<ITraceEntry[]>([]);
  const [expandedTraceMap, setExpandedTraceMap] = useState<Record<string, boolean>>({});
  const [streamThoughtPulse, setStreamThoughtPulse] = useState(false);
  const [prefillInputState, setPrefillInputState] = useState<{ text: string; token: number } | null>(null);
  const [currentRoundUserMessageId, setCurrentRoundUserMessageId] = useState<string | null>(null);
  const [messageListContentHeight, setMessageListContentHeight] = useState(0);

  // Session management.
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [currentSessionTitle, setCurrentSessionTitle] = useState<string>('');
  const [openSettings, setOpenSettings] = useState(false);
  const [sessionLoading, setSessionLoading] = useState(false);
  const isEmptyState = !messages.length && !streamingText && !streamTraceEntries.length;

  const streamingRef = useRef('');
  const streamTraceEntriesRef = useRef<ITraceEntry[]>([]);
  const previousStatusRef = useRef<SSERequestStatus>(SSERequestStatus.IDLE);
  const previousStreamThoughtPreviewRef = useRef('');
  const streamThoughtPulseTimerRef = useRef<number | null>(null);
  const newSessionIdRef = useRef<string | null>(null);
  const currentSessionIdRef = useRef<string | null>(null);
  const currentSessionTitleRef = useRef('');
  const messagesRef = useRef<IChatItem[]>([]);
  const currentRoundUserMessageIdRef = useRef<string | null>(null);
  const statusRef = useRef<SSERequestStatus>(SSERequestStatus.IDLE);
  const inProgressSessionRef = useRef<IInProgressSessionSnapshot | null>(null);
  const chatInputRef = useRef<ChatInputPropsRef>(null);
  const messageListRef = useRef<HTMLDivElement>(null);
  const messageContentRef = useRef<HTMLDivElement>(null);
  const bottomSentinelRef = useRef<HTMLDivElement>(null);
  const messageElementMapRef = useRef<Map<string, HTMLDivElement>>(new Map());
  const pendingViewportAnchorRef = useRef<string | null>(null);
  const currentRoundBlockRef = useRef<HTMLDivElement | null>(null);
  const autoFollowRef = useRef(true);
  const suppressScrollTrackingRef = useRef(false);
  const initialViewportAnimationTimerRef = useRef<number | null>(null);
  const initialViewportAnimatingRef = useRef(false);
  const programmaticScrollTimerRef = useRef<number | null>(null);
  const topAlignmentTimerRef = useRef<number | null>(null);
  const pendingInitialBottomSyncRef = useRef(false);
  const initialBottomSyncTimerRef = useRef<number | null>(null);

  const lockScrollTracking = useCallback((behavior: ScrollBehavior = 'auto') => {
    suppressScrollTrackingRef.current = true;
    if (programmaticScrollTimerRef.current !== null) {
      window.clearTimeout(programmaticScrollTimerRef.current);
    }
    const delay = behavior === 'smooth' ? INITIAL_VIEWPORT_ANIMATION_MS + 120 : PROGRAMMATIC_SCROLL_LOCK_MS;
    programmaticScrollTimerRef.current = window.setTimeout(() => {
      suppressScrollTrackingRef.current = false;
      programmaticScrollTimerRef.current = null;
    }, delay);
  }, []);

  const correctMessageTopAlignment = useCallback((messageId: string) => {
    const container = messageListRef.current;
    const messageElement = messageElementMapRef.current.get(messageId);
    if (!container || !messageElement) {
      return;
    }
    const containerRect = container.getBoundingClientRect();
    const messageRect = messageElement.getBoundingClientRect();
    const delta = messageRect.top - containerRect.top - MESSAGE_TOP_ALIGNMENT_GAP;

    if (Math.abs(delta) <= 1) {
      return;
    }

    lockScrollTracking('auto');
    container.scrollTop += delta;
  }, [lockScrollTracking]);

  const scrollMessageToTop = useCallback((messageId: string, behavior: ScrollBehavior = 'auto') => {
    const container = messageListRef.current;
    const messageElement = messageElementMapRef.current.get(messageId);
    if (!container || !messageElement) {
      return false;
    }
    lockScrollTracking(behavior);
    const nextScrollTop = Math.max(messageElement.offsetTop - MESSAGE_TOP_ALIGNMENT_GAP, 0);
    container.scrollTo({
      top: nextScrollTop,
      behavior,
    });

    if (topAlignmentTimerRef.current !== null) {
      window.clearTimeout(topAlignmentTimerRef.current);
      topAlignmentTimerRef.current = null;
    }

    if (behavior === 'smooth') {
      topAlignmentTimerRef.current = window.setTimeout(() => {
        correctMessageTopAlignment(messageId);
        topAlignmentTimerRef.current = null;
      }, INITIAL_VIEWPORT_ANIMATION_MS);
    } else {
      requestAnimationFrame(() => {
        correctMessageTopAlignment(messageId);
      });
    }

    return true;
  }, [correctMessageTopAlignment, lockScrollTracking]);

  const scrollMessageListToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const container = messageListRef.current;
    if (!container) {
      return;
    }
    lockScrollTracking(behavior);
    if (bottomSentinelRef.current) {
      bottomSentinelRef.current.scrollIntoView({
        block: 'end',
        behavior,
      });
      return;
    }
    container.scrollTo({
      top: container.scrollHeight,
      behavior,
    });
  }, [lockScrollTracking]);

  const getMessageListContentHeight = useCallback(() => {
    const container = messageListRef.current;
    if (!container) {
      return 0;
    }
    const containerStyle = window.getComputedStyle(container);
    const paddingTop = parseFloat(containerStyle.paddingTop || '0') || 0;
    const paddingBottom = parseFloat(containerStyle.paddingBottom || '0') || 0;
    return Math.max(Math.floor(container.clientHeight - paddingTop - paddingBottom), 0);
  }, []);

  const isCurrentRoundOverflowingViewport = useCallback(() => {
    const roundBlock = currentRoundBlockRef.current;
    if (!roundBlock) {
      return true;
    }
    const availableHeight = getMessageListContentHeight();
    if (availableHeight <= 0) {
      return false;
    }
    return roundBlock.offsetHeight > availableHeight;
  }, [getMessageListContentHeight]);

  const ensureMessageListAtBottom = useCallback(() => {
    if (!pendingInitialBottomSyncRef.current) {
      return;
    }
    if (sessionLoading) {
      return;
    }
    const container = messageListRef.current;
    if (!container) {
      return;
    }

    scrollMessageListToBottom();

    requestAnimationFrame(() => {
      if (!pendingInitialBottomSyncRef.current) {
        return;
      }
      scrollMessageListToBottom();
    });

    if (initialBottomSyncTimerRef.current !== null) {
      window.clearTimeout(initialBottomSyncTimerRef.current);
    }
    initialBottomSyncTimerRef.current = window.setTimeout(() => {
      if (!pendingInitialBottomSyncRef.current) {
        return;
      }
      scrollMessageListToBottom();
      pendingInitialBottomSyncRef.current = false;
      initialBottomSyncTimerRef.current = null;
    }, 180);
  }, [scrollMessageListToBottom, sessionLoading]);

  const setAutoFollow = useCallback((nextValue: boolean) => {
    autoFollowRef.current = nextValue;
  }, []);

  const handleMessageListScroll = useCallback(() => {
    if (suppressScrollTrackingRef.current) {
      return;
    }
    const container = messageListRef.current;
    if (!container) {
      return;
    }
    const isAtBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight <= SCROLL_BOTTOM_THRESHOLD;
    setAutoFollow(isAtBottom);
  }, [setAutoFollow]);

  const setMessageElement = useCallback((id: string, node: HTMLDivElement | null) => {
    if (node) {
      messageElementMapRef.current.set(id, node);
      if (pendingViewportAnchorRef.current === id) {
        requestAnimationFrame(() => {
          const anchored = scrollMessageToTop(id);
          if (anchored && pendingViewportAnchorRef.current === id) {
            pendingViewportAnchorRef.current = null;
          }
        });
      }
      return;
    }
    messageElementMapRef.current.delete(id);
  }, [scrollMessageToTop]);

  const flushPendingBuffer = useCallback(() => {
    return;
  }, []);

  // Panel mode session list.
  const [sessionList, setSessionList] = useState<IChatSession[]>([]);

  const { selectedModel, setSelectedModel, setShowPanel } = useAIStore((state) => ({
    selectedModel: state.selectedModel,
    setSelectedModel: state.setSelectedModel,
    setShowPanel: state.setShowPanel,
  }));

  const handleChunk = useCallback((rawChunk, parsedData) => {
    const chunk = parsedData as unknown as IStreamChunk;
    const messageType =
      chunk?.messageType || chunk?.type || (typeof rawChunk?.event === 'string' ? rawChunk.event : undefined);
    const activeSessionId = currentSessionIdRef.current || '';
    const inProgressSession = inProgressSessionRef.current;
    const isHiddenInProgress =
      !!inProgressSession && (!inProgressSession.sessionId || inProgressSession.sessionId !== activeSessionId);

    if (messageType === 'session' && chunk.sessionId) {
      newSessionIdRef.current = chunk.sessionId;
      if (inProgressSession && !inProgressSession.sessionId) {
        inProgressSession.sessionId = chunk.sessionId;
      }
      if (!currentSessionIdRef.current) {
        currentSessionIdRef.current = chunk.sessionId;
        setCurrentSessionId(chunk.sessionId);
      }
      window.dispatchEvent(new CustomEvent('stream:sessionsChanged'));
      return;
    }

    if (messageType === 'answer') {
      const delta = chunk.content || '';
      if (isHiddenInProgress && inProgressSession) {
        inProgressSession.streamingText += delta;
        return;
      }
      setStreamingText((prev) => {
        const next = prev + delta;
        streamingRef.current = next;
        return next;
      });
      return;
    }

    if (messageType === 'reasoning' || messageType === 'tool_call' || messageType === 'tool_result') {
      const traceEntry = normalizeTraceEntry({
        ...chunk,
        type: messageType,
      });
      if (traceEntry) {
        if (isHiddenInProgress && inProgressSession) {
          inProgressSession.traceEntries = mergeTraceEntries(inProgressSession.traceEntries, traceEntry);
          return;
        }
        setStreamTraceEntries((prev) => {
          const next = mergeTraceEntries(prev, traceEntry);
          streamTraceEntriesRef.current = next;
          return next;
        });
      }
      return;
    }

    if (messageType === 'done') {
      // Server may return sessionId in done event as fallback.
      if (chunk.sessionId) {
        newSessionIdRef.current = chunk.sessionId;
        if (inProgressSession && !inProgressSession.sessionId) {
          inProgressSession.sessionId = chunk.sessionId;
        }
      }
      return;
    }

    if (messageType === 'error') {
      const traceEntry = normalizeTraceEntry({
        ...chunk,
        type: 'error',
      });
      if (traceEntry) {
        if (isHiddenInProgress && inProgressSession) {
          inProgressSession.traceEntries = mergeTraceEntries(inProgressSession.traceEntries, traceEntry);
        } else {
          setStreamTraceEntries((prev) => {
            const next = mergeTraceEntries(prev, traceEntry);
            streamTraceEntriesRef.current = next;
            return next;
          });
        }
      }
      feedback.error(chunk.content || 'AI stream error');
    }
  }, []);

  const { status, request, stop } = useSSERequest<IStreamChunk>(
    {
      baseURL: '/api/v3/ai/chat/stream',
      onChunk: handleChunk,
    },
    undefined,
  );

  // Load the model list.

  const loadModelOptions = useCallback(async () => {
    try {
      const result = (await listAvailableModelOptions()) || [];
      const optionMap: Record<string, IModelOptionItem> = {};
      result.forEach((item) => {
        optionMap[item.value] = item;
      });
      setModelOptionMap(optionMap);
      setModelOptions(
        result.map((item) => ({
          label: item.label,
          value: item.value,
          isDefault: !!item.defaultOption,
        })),
      );
      const currentValue = selectedModel?.value;
      const currentOption = currentValue ? result.find((item) => item.value === currentValue) : undefined;
      const hasCurrent = !!currentOption;
      if (currentOption && currentOption.label !== selectedModel?.label) {
        setSelectedModel({
          value: currentOption.value,
          label: currentOption.label,
        });
        return;
      }
      if (!hasCurrent && result.length > 0) {
        const defaultOption = result.find((item) => item.defaultOption) || result[0];
        setSelectedModel({
          value: defaultOption.value,
          label: defaultOption.label,
        });
      }
    } catch {
      setModelOptions([]);
      setModelOptionMap({});
      feedback.error(i18n('stream.error.loadModelList'));
    }
  }, [selectedModel?.label, selectedModel?.value, setSelectedModel]);

  useEffect(() => {
    loadModelOptions();
  }, []);

  useEffect(() => {
    currentSessionIdRef.current = currentSessionId;
  }, [currentSessionId]);

  useEffect(() => {
    currentSessionTitleRef.current = currentSessionTitle;
  }, [currentSessionTitle]);

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  useEffect(() => {
    currentRoundUserMessageIdRef.current = currentRoundUserMessageId;
  }, [currentRoundUserMessageId]);

  useEffect(() => {
    statusRef.current = status;
  }, [status]);

  useEffect(() => {
    return () => {
      if (streamThoughtPulseTimerRef.current !== null) {
        window.clearTimeout(streamThoughtPulseTimerRef.current);
        streamThoughtPulseTimerRef.current = null;
      }
      if (initialViewportAnimationTimerRef.current !== null) {
        window.clearTimeout(initialViewportAnimationTimerRef.current);
        initialViewportAnimationTimerRef.current = null;
      }
      if (programmaticScrollTimerRef.current !== null) {
        window.clearTimeout(programmaticScrollTimerRef.current);
        programmaticScrollTimerRef.current = null;
      }
      if (topAlignmentTimerRef.current !== null) {
        window.clearTimeout(topAlignmentTimerRef.current);
        topAlignmentTimerRef.current = null;
      }
      if (initialBottomSyncTimerRef.current !== null) {
        window.clearTimeout(initialBottomSyncTimerRef.current);
        initialBottomSyncTimerRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    const previewText = getTracePreview(streamTraceEntries[streamTraceEntries.length - 1]);
    if (!previewText || previewText === previousStreamThoughtPreviewRef.current) {
      return;
    }
    previousStreamThoughtPreviewRef.current = previewText;
    setStreamThoughtPulse(true);
    if (streamThoughtPulseTimerRef.current !== null) {
      window.clearTimeout(streamThoughtPulseTimerRef.current);
    }
    streamThoughtPulseTimerRef.current = window.setTimeout(() => {
      setStreamThoughtPulse(false);
      streamThoughtPulseTimerRef.current = null;
    }, 2400);
  }, [streamTraceEntries]);

  // Load the session list in panel mode.

  const fetchSessionList = useCallback(async () => {
    try {
      const sessions = (await aiStreamService.getChatSessions(undefined as void)) || [];
      setSessionList(sessions);
    } catch {
      // silent
    }
  }, []);

  useEffect(() => {
    if (isPanel) {
      fetchSessionList();
    }
  }, [isPanel]);

  useEffect(() => {
    const container = messageListRef.current;
    if (!container) {
      return;
    }

    const syncMessageListContentHeight = () => {
      setMessageListContentHeight((prev) => {
        const next = getMessageListContentHeight();
        return prev === next ? prev : next;
      });
    };

    syncMessageListContentHeight();

    const resizeObserver = new ResizeObserver(() => {
      syncMessageListContentHeight();
    });

    resizeObserver.observe(container);

    return () => {
      resizeObserver.disconnect();
    };
  }, [getMessageListContentHeight, isEmptyState, sessionLoading]);

  useEffect(() => {
    if (!isPanel) return;

    const handlePrefillEvent = (e: Event) => {
      const { input } = (e as CustomEvent<{ input: string }>).detail || {};
      if (!input) return;
      setPrefillInputState({
        text: input,
        token: Date.now(),
      });
    };

    window.addEventListener('stream:prefillMessage', handlePrefillEvent);
    return () => {
      window.removeEventListener('stream:prefillMessage', handlePrefillEvent);
    };
  }, [isPanel]);

  // Refresh the list when sessionsChanged is emitted.
  useEffect(() => {
    if (!isPanel) return;
    const handler = () => fetchSessionList();
    window.addEventListener('stream:sessionsChanged', handler);
    return () => window.removeEventListener('stream:sessionsChanged', handler);
  }, [isPanel, fetchSessionList]);

  // URL helpers for the /stream/:chatId path format.

  const getChatIdFromPath = useCallback(() => {
    const parts = window.location.pathname.split('/');
    // pathname: /stream/2a7b72bc-...
    if (parts[1] === 'stream' && parts[2]) {
      return parts[2];
    }
    return null;
  }, []);

  const setChatIdInPath = useCallback((chatId: string) => {
    // Update only from a /stream path so other page URLs are not overwritten.
    if (window.location.pathname.startsWith('/stream')) {
      window.history.pushState({}, '', `/stream/${chatId}`);
    }
  }, []);

  const clearChatIdFromPath = useCallback(() => {
    // Reset to /stream only from /stream/:chatId so other page URLs remain intact.
    if (window.location.pathname.startsWith('/stream/')) {
      window.history.pushState({}, '', '/stream');
    }
  }, []);

  // Add the AI response locally and update the session ID when SSE completes.

  useEffect(() => {
    const previousStatus = previousStatusRef.current;
    previousStatusRef.current = status;

    if (status !== SSERequestStatus.FINISH || previousStatus === SSERequestStatus.FINISH) {
      return;
    }

    // Flush any buffered content immediately when the stream ends.
    flushPendingBuffer();

    const activeSessionId = currentSessionIdRef.current || '';
    const inProgressSession = inProgressSessionRef.current;
    const hiddenInProgress =
      inProgressSession && (!inProgressSession.sessionId || inProgressSession.sessionId !== activeSessionId)
        ? inProgressSession
        : null;
    const finalTraceEntries = streamTraceEntriesRef.current;

    if (hiddenInProgress) {
      if (hiddenInProgress.streamingText.trim() || hiddenInProgress.traceEntries.length) {
        hiddenInProgress.messages = [
          ...hiddenInProgress.messages,
          {
            id: `${Date.now()}-${Math.random()}`,
            role: 'assistant',
            content: hiddenInProgress.streamingText,
            traceEntries: hiddenInProgress.traceEntries,
          },
        ];
      }
      hiddenInProgress.streamingText = '';
      hiddenInProgress.traceEntries = [];
      hiddenInProgress.currentRoundUserMessageId = null;
    } else if (streamingRef.current.trim() || finalTraceEntries.length) {
      const content = streamingRef.current;
      setMessages((prev) => [
        ...prev,
        {
          id: `${Date.now()}-${Math.random()}`,
          role: 'assistant',
          content,
          traceEntries: finalTraceEntries,
        },
      ]);
    }

    // Update the session ID returned by the backend for a new session.
    if (newSessionIdRef.current) {
      const newId = newSessionIdRef.current;
      if (hiddenInProgress) {
        hiddenInProgress.sessionId = newId;
      } else {
        setCurrentSessionId(newId);
        currentSessionIdRef.current = newId;
        // Write the new session ID to the address bar path.
        if (!isPanel) {
          setChatIdInPath(newId);
        }
      }
      newSessionIdRef.current = null;
      // Notify the sidebar to refresh its session list.
      window.dispatchEvent(new CustomEvent('stream:sessionsChanged'));
    }

    streamingRef.current = '';
    setStreamingText('');
    streamTraceEntriesRef.current = [];
    setStreamTraceEntries([]);
    previousStreamThoughtPreviewRef.current = '';
    setStreamThoughtPulse(false);
    setCurrentRoundUserMessageId(null);
    currentRoundUserMessageIdRef.current = null;
  }, [status, flushPendingBuffer, isPanel, setChatIdInPath]);

  // Automatic scrolling.

  useEffect(() => {
    const contentElement = messageContentRef.current;
    if (!contentElement) {
      return;
    }

    const resizeObserver = new ResizeObserver(() => {
      if (initialViewportAnimatingRef.current) {
        return;
      }
      if (!autoFollowRef.current) {
        return;
      }
      if (!isCurrentRoundOverflowingViewport()) {
        return;
      }
      scrollMessageListToBottom();
    });

    resizeObserver.observe(contentElement);

    return () => {
      resizeObserver.disconnect();
    };
  }, [isCurrentRoundOverflowingViewport, isEmptyState, scrollMessageListToBottom, sessionLoading]);

  useEffect(() => {
    if (!pendingInitialBottomSyncRef.current) {
      return;
    }
    ensureMessageListAtBottom();
  }, [ensureMessageListAtBottom, messages, sessionLoading, streamTraceEntries.length]);

  useEffect(() => {
    if (pendingViewportAnchorRef.current) {
      const behavior = initialViewportAnimatingRef.current ? 'smooth' : 'auto';
      const anchored = scrollMessageToTop(pendingViewportAnchorRef.current, behavior);
      if (anchored) {
        pendingViewportAnchorRef.current = null;
      }
    }
    if (initialViewportAnimatingRef.current) {
      return;
    }
    if (!isCurrentRoundOverflowingViewport()) {
      return;
    }
    if (!autoFollowRef.current) {
      return;
    }
    scrollMessageListToBottom();
  }, [
    messages,
    streamingText,
    streamTraceEntries.length,
    currentRoundUserMessageId,
    messageListContentHeight,
    isCurrentRoundOverflowingViewport,
    scrollMessageListToBottom,
    scrollMessageToTop,
  ]);

  // Start a new conversation.

  const handleNewChat = useCallback(() => {
    stop();
    setAutoFollow(true);
    chatInputRef.current?.resetAttachments();
    pendingViewportAnchorRef.current = null;
    pendingInitialBottomSyncRef.current = false;
    initialViewportAnimatingRef.current = false;
    if (initialViewportAnimationTimerRef.current !== null) {
      window.clearTimeout(initialViewportAnimationTimerRef.current);
      initialViewportAnimationTimerRef.current = null;
    }
    if (topAlignmentTimerRef.current !== null) {
      window.clearTimeout(topAlignmentTimerRef.current);
      topAlignmentTimerRef.current = null;
    }
    if (initialBottomSyncTimerRef.current !== null) {
      window.clearTimeout(initialBottomSyncTimerRef.current);
      initialBottomSyncTimerRef.current = null;
    }
    setMessages([]);
    messagesRef.current = [];
    setStreamingText('');
    streamingRef.current = '';
    streamTraceEntriesRef.current = [];
    setStreamTraceEntries([]);
    previousStreamThoughtPreviewRef.current = '';
    setStreamThoughtPulse(false);
    setExpandedTraceMap({});
    setCurrentSessionId(null);
    currentSessionIdRef.current = null;
    setCurrentSessionTitle('');
    currentSessionTitleRef.current = '';
    setCurrentRoundUserMessageId(null);
    currentRoundUserMessageIdRef.current = null;
    currentRoundBlockRef.current = null;
    newSessionIdRef.current = null;
    inProgressSessionRef.current = null;
    if (!isPanel) {
      clearChatIdFromPath();
    }
    onSessionChange?.();
  }, [isPanel, clearChatIdFromPath, onSessionChange, stop]);

  const handleDeleteHistorySession = useCallback(
    async (sessionId: string) => {
      try {
        await aiStreamService.deleteChatSession({ id: sessionId });
        setSessionList((prev) => prev.filter((item) => item.id !== sessionId));

        if (currentSessionIdRef.current === sessionId || newSessionIdRef.current === sessionId) {
          handleNewChat();
        }

        window.dispatchEvent(new CustomEvent('stream:sessionsChanged'));
        feedback.success(i18n('common.text.successfullyDelete'));
      } catch {
        feedback.error(i18n('stream.sidebar.deleteFailed'));
      }
    },
    [handleNewChat],
  );

  const confirmDeleteHistorySession = useCallback(
    (sessionId: string) => {
      Modal.confirm({
        title: i18n('stream.sidebar.deleteConfirm'),
        okText: i18n('common.button.delete'),
        cancelText: i18n('common.button.cancel'),
        okButtonProps: { danger: true },
        onOk: () => handleDeleteHistorySession(sessionId),
      });
    },
    [handleDeleteHistorySession],
  );

  // Load a historical conversation by session ID.

  const handleLoadSessionById = useCallback(
    async (sessionId: string, title?: string) => {
      const isGenerating = statusRef.current === SSERequestStatus.LOADING;
      if (isGenerating) {
        const activeSessionId = currentSessionIdRef.current || '';
        const inProgressSession = inProgressSessionRef.current;
        if (!inProgressSession && activeSessionId !== sessionId) {
          inProgressSessionRef.current = {
            sessionId: activeSessionId,
            title: currentSessionTitleRef.current,
            messages: [...messagesRef.current],
            streamingText: streamingRef.current,
            traceEntries: [...streamTraceEntriesRef.current],
            currentRoundUserMessageId: currentRoundUserMessageIdRef.current,
          };
        }
      } else {
        // Stop the historical stream when switching outside generation to avoid stale events.
        stop();
      }

      setAutoFollow(true);
      chatInputRef.current?.resetAttachments();
      pendingViewportAnchorRef.current = null;
      pendingInitialBottomSyncRef.current = true;
      initialViewportAnimatingRef.current = false;
      if (initialViewportAnimationTimerRef.current !== null) {
        window.clearTimeout(initialViewportAnimationTimerRef.current);
        initialViewportAnimationTimerRef.current = null;
      }
      if (topAlignmentTimerRef.current !== null) {
        window.clearTimeout(topAlignmentTimerRef.current);
        topAlignmentTimerRef.current = null;
      }
      if (initialBottomSyncTimerRef.current !== null) {
        window.clearTimeout(initialBottomSyncTimerRef.current);
        initialBottomSyncTimerRef.current = null;
      }
      // Clear stale data and update the title immediately when available.
      onSessionChange?.();
      setMessages([]);
      messagesRef.current = [];
      setStreamingText('');
      streamingRef.current = '';
      streamTraceEntriesRef.current = [];
      setStreamTraceEntries([]);
      previousStreamThoughtPreviewRef.current = '';
      setStreamThoughtPulse(false);
      setExpandedTraceMap({});
      setCurrentSessionId(sessionId);
      currentSessionIdRef.current = sessionId;
      setCurrentSessionTitle(title || '');
      currentSessionTitleRef.current = title || '';
      setCurrentRoundUserMessageId(null);
      currentRoundUserMessageIdRef.current = null;
      currentRoundBlockRef.current = null;

      const inProgressSession = inProgressSessionRef.current;
      if (inProgressSession && inProgressSession.sessionId === sessionId) {
        setMessages(inProgressSession.messages);
        messagesRef.current = [...inProgressSession.messages];
        setStreamingText(inProgressSession.streamingText);
        streamingRef.current = inProgressSession.streamingText;
        setStreamTraceEntries(inProgressSession.traceEntries);
        streamTraceEntriesRef.current = [...inProgressSession.traceEntries];
        setCurrentRoundUserMessageId(inProgressSession.currentRoundUserMessageId);
        currentRoundUserMessageIdRef.current = inProgressSession.currentRoundUserMessageId;
        if (!title && inProgressSession.title) {
          setCurrentSessionTitle(inProgressSession.title);
          currentSessionTitleRef.current = inProgressSession.title;
        }
        inProgressSessionRef.current = null;
        return;
      }

      setSessionLoading(true);
      try {
        const msgs = (await aiStreamService.getChatMessages({ sessionId })) || [];
        const chatItems: IChatItem[] = msgs.map((m: IChatMessage) => ({
          id: m.id,
          role: m.role as ChatRole,
          content: m.content,
          attachments: m.attachments,
          traceEntries: parseTraceEntries(m.reasoningContent),
        }));
        const latestInProgressSession = inProgressSessionRef.current;
        const shouldRestoreHiddenInProgress =
          !!latestInProgressSession &&
          (!latestInProgressSession.sessionId || latestInProgressSession.sessionId === sessionId) &&
          isLikelySameSessionFromPrefix(chatItems, latestInProgressSession.messages);

        if (shouldRestoreHiddenInProgress && latestInProgressSession) {
          latestInProgressSession.sessionId = sessionId;
          if (title) {
            latestInProgressSession.title = title;
          }
          setMessages(latestInProgressSession.messages);
          messagesRef.current = [...latestInProgressSession.messages];
          setStreamingText(latestInProgressSession.streamingText);
          streamingRef.current = latestInProgressSession.streamingText;
          setStreamTraceEntries(latestInProgressSession.traceEntries);
          streamTraceEntriesRef.current = [...latestInProgressSession.traceEntries];
          setCurrentRoundUserMessageId(latestInProgressSession.currentRoundUserMessageId);
          currentRoundUserMessageIdRef.current = latestInProgressSession.currentRoundUserMessageId;
          if (!title && latestInProgressSession.title) {
            setCurrentSessionTitle(latestInProgressSession.title);
            currentSessionTitleRef.current = latestInProgressSession.title;
          }
          inProgressSessionRef.current = null;
          return;
        }

        setMessages(chatItems);
        messagesRef.current = [...chatItems];

        // Fill the title from the session list when opening the URL directly.
        if (!title) {
          try {
            const sessions = (await aiStreamService.getChatSessions(undefined as void)) || [];
            const found = sessions.find((s) => s.id === sessionId);
            setCurrentSessionTitle(found?.title || '');
            currentSessionTitleRef.current = found?.title || '';
          } catch {
            // silent
          }
        }
      } catch {
        feedback.error(i18n('stream.error.loadSessionMessages'));
      } finally {
        setSessionLoading(false);
      }
    },
    [onSessionChange, stop],
  );

  // Restore the conversation from the path when first opening /stream/:chatId.

  const mountedRef = useRef(false);
  useEffect(() => {
    if (mountedRef.current) return;
    mountedRef.current = true;
    if (!isPanel) {
      const chatId = getChatIdFromPath();
      if (chatId) {
        handleLoadSessionById(chatId);
      }
    }
  }, []);

  // Handle stream:newChat in every mode, including the Cmd+L shortcut.

  useEffect(() => {
    const handleNewChatEvent = () => {
      handleNewChat();
    };
    window.addEventListener('stream:newChat', handleNewChatEvent);
    return () => {
      window.removeEventListener('stream:newChat', handleNewChatEvent);
    };
  }, [handleNewChat]);

  // Handle sidebar events only in page mode.

  useEffect(() => {
    if (isPanel) return;

    const handleLoadEvent = (e: Event) => {
      const { sessionId, title } = (e as CustomEvent).detail;
      handleLoadSessionById(sessionId, title);
    };

    window.addEventListener('stream:loadSession', handleLoadEvent);
    return () => {
      window.removeEventListener('stream:loadSession', handleLoadEvent);
    };
  }, [isPanel, handleLoadSessionById]);

  // Send a message.

  const handleSend = useCallback(
    async (params: SendParams) => {
      const content = (params.input || '').trim();
      if (!content) return;

      const selectedValue = params.model || selectedModel?.value;
      if (!selectedValue) {
        feedback.warning(i18n('stream.warning.selectModel'));
        return;
      }
      const selectedOption = modelOptionMap[selectedValue];
      if (!selectedOption) {
        feedback.warning(i18n('stream.warning.invalidModel'));
        return;
      }

      setStreamTraceEntries([]);
      streamTraceEntriesRef.current = [];
      previousStreamThoughtPreviewRef.current = '';
      setStreamThoughtPulse(false);
      setExpandedTraceMap({});
      setStreamingText('');
      streamingRef.current = '';
      inProgressSessionRef.current = null;

      const userMessageId = `${Date.now()}-${Math.random()}`;
      previousStatusRef.current = SSERequestStatus.LOADING;
      setAutoFollow(true);
      initialViewportAnimatingRef.current = true;
      if (initialViewportAnimationTimerRef.current !== null) {
        window.clearTimeout(initialViewportAnimationTimerRef.current);
      }
      initialViewportAnimationTimerRef.current = window.setTimeout(() => {
        initialViewportAnimatingRef.current = false;
        initialViewportAnimationTimerRef.current = null;
        if (autoFollowRef.current && isCurrentRoundOverflowingViewport()) {
          scrollMessageListToBottom();
        }
      }, INITIAL_VIEWPORT_ANIMATION_MS);
      pendingViewportAnchorRef.current = userMessageId;
      setCurrentRoundUserMessageId(userMessageId);
      currentRoundUserMessageIdRef.current = userMessageId;
      currentRoundBlockRef.current = null;
      setMessages((prev) => {
        const next = [
          ...prev,
          {
            id: userMessageId,
            role: 'user' as const,
            content,
            attachments: params.attachments,
          },
        ];
        messagesRef.current = next;
        return next;
      });

      // Let the backend load history for an existing session; otherwise send local history.
      const historyPayload = currentSessionId
        ? []
        : messages
            .slice(-MAX_HISTORY_ROUNDS * 2)
            .filter((item) => item.content?.trim())
            .map((item) => ({ role: item.role, content: item.content }));

      const modelRequestPayload = await resolveModelRequestPayload(selectedOption);
      if (!modelRequestPayload) {
        feedback.warning(i18n('stream.warning.invalidModel'));
        return;
      }

      console.log('[AI stream] sending request', {
        inputPreview: content.slice(0, 200),
        sessionId: currentSessionId || undefined,
        dataSourceId: params.dataSourceId,
        dataSourceCollectionId: params.dataSourceCollectionId,
        databaseName: params.databaseName,
        schemaName: params.schemaName,
        attachmentCount: params.attachments?.length || 0,
        attachments: (params.attachments || []).map((attachment) => ({
          fileName: attachment.fileName,
          fileType: attachment.fileType,
          contentCategory: attachment.contentCategory,
          contentLength: attachment.contentLength,
          truncated: attachment.truncated,
          contentPreview: attachment.content?.slice(0, 120),
        })),
      });

      const isNewSession = !currentSessionId;
      const requestPromise = request({
        input: content,
        sessionId: currentSessionId || undefined,
        history: historyPayload,
        enableTools: true,
        ...modelRequestPayload,
        dataSourceCollectionId: params.dataSourceCollectionId,
        dataSourceId: params.dataSourceId,
        databaseName: params.databaseName,
        schemaName: params.schemaName,
        attachments: params.attachments,
      });

      if (isNewSession) {
        window.dispatchEvent(new CustomEvent('stream:sessionsChanged'));
        window.setTimeout(() => {
          window.dispatchEvent(new CustomEvent('stream:sessionsChanged'));
        }, 600);
      }

      await requestPromise;
    },
    [
      currentSessionId,
      isCurrentRoundOverflowingViewport,
      messages,
      modelOptionMap,
      selectedModel?.value,
      request,
      scrollMessageListToBottom,
    ],
  );

  // Handle externally triggered messages, such as context-menu actions.

  useEffect(() => {
    // Respond only in panel mode so a page-mode instance cannot rewrite the URL.
    if (!isPanel) return;

    const handleSendEvent = (e: Event) => {
      const params = (e as CustomEvent).detail as SendParams;
      if (params) {
        // Start a new conversation before sending to avoid mixing old context.
        handleNewChat();
        // Wait for handleNewChat state cleanup before sending.
        setTimeout(() => {
          handleSend(params);
        }, 0);
      }
    };

    window.addEventListener('stream:sendMessage', handleSendEvent);
    return () => {
      window.removeEventListener('stream:sendMessage', handleSendEvent);
    };
  }, [isPanel, handleSend, handleNewChat]);

  // Handle table-name clicks.

  const handleTableClick = useCallback(
    (tableName: string) => {
      // Read the current cascader context from the AI store.
      const page = isPanel ? 'workspace' : (useGlobalStore.getState().mainPageActiveTab as any) || 'stream';
      const cascaderData = useAIStore.getState().cascaderDataMap[page];
      const dataSourceId = cascaderData && 'dataSourceId' in cascaderData ? cascaderData.dataSourceId : undefined;
      const databaseName = cascaderData && 'databaseName' in cascaderData ? cascaderData.databaseName : undefined;
      const schemaName = cascaderData && 'schemaName' in cascaderData ? cascaderData.schemaName : undefined;

      // Prefer databaseType and dataSourceName from the cascader selection.
      // Fall back to the tree store because treeData may not be loaded on Stream pages.
      let databaseType: DatabaseTypeCode | undefined =
        cascaderData && 'databaseType' in cascaderData ? cascaderData.databaseType : undefined;
      let dataSourceName: string | undefined =
        cascaderData && 'dataSourceName' in cascaderData ? cascaderData.dataSourceName : undefined;
      if (!databaseType || !dataSourceName) {
        const treeData = useTreeStore.getState().treeData;
        if (treeData && dataSourceId) {
          const dsNode = treeData.find((n) => n.extraParams?.dataSourceId === dataSourceId);
          if (dsNode) {
            databaseType = databaseType || dsNode.extraParams?.databaseType;
            dataSourceName = dataSourceName || dsNode.extraParams?.dataSourceName;
          }
        }
      }

      const context: ITableClickContext = { dataSourceId, databaseName, schemaName, databaseType, dataSourceName };

      if (onTableClick) {
        onTableClick(tableName, context);
        return;
      }

      // Open a tab directly by default in workspace panel mode.
      if (isPanel && dataSourceId && databaseType) {
        const _tableName = compatibleDataBaseName(tableName, databaseType);
        const title = [tableName].filter(Boolean).join('.') + (dataSourceName ? `[${dataSourceName}]` : '');
        const id = `${OperationColumn.OpenTable}-${dataSourceId}-${databaseName || ''}-${
          schemaName || ''
        }-${tableName}`;
        useWorkspaceStore.getState().addWorkspaceTab({
          id,
          title,
          type: WorkspaceTabType.EditTableData,
          uniqueData: {
            dataSourceId,
            databaseName,
            schemaName,
            databaseType,
            dataSourceName,
            tableName,
            sql: 'select * from ' + _tableName,
          },
        });
      }
    },
    [isPanel, onTableClick],
  );

  // Pin SQL to a console.

  const handlePinSql = useCallback(
    (sql: string) => {
      const page = isPanel ? 'workspace' : (useGlobalStore.getState().mainPageActiveTab as any) || 'stream';
      const cascaderData = useAIStore.getState().cascaderDataMap[page];
      const dataSourceId = cascaderData && 'dataSourceId' in cascaderData ? cascaderData.dataSourceId : undefined;
      const databaseName = cascaderData && 'databaseName' in cascaderData ? cascaderData.databaseName : undefined;
      const schemaName = cascaderData && 'schemaName' in cascaderData ? cascaderData.schemaName : undefined;

      // Prefer databaseType and dataSourceName from the cascader selection.
      // This also works on pages such as Stream where treeData is not loaded.
      let databaseType: DatabaseTypeCode | undefined =
        cascaderData && 'databaseType' in cascaderData ? cascaderData.databaseType : undefined;
      let dataSourceName: string | undefined =
        cascaderData && 'dataSourceName' in cascaderData ? cascaderData.dataSourceName : undefined;
      if (!databaseType || !dataSourceName) {
        const treeData = useTreeStore.getState().treeData;
        if (treeData && dataSourceId) {
          const dsNode = treeData.find((n) => n.extraParams?.dataSourceId === dataSourceId);
          if (dsNode) {
            databaseType = databaseType || dsNode.extraParams?.databaseType;
            dataSourceName = dataSourceName || dsNode.extraParams?.dataSourceName;
          }
        }
      }

      if (!dataSourceId || !databaseType || !dataSourceName) {
        feedback.warning(i18n('stream.warning.selectDataSource'));
        return;
      }

      const context: ITableClickContext = { dataSourceId, databaseName, schemaName, databaseType, dataSourceName };

      if (onPinSql) {
        onPinSql(sql, context);
        return;
      }

      // Create a console and switch to the workspace by default.
      useWorkspaceStore.getState().createConsole({
        dataSourceId,
        dataSourceName,
        databaseType,
        databaseName,
        schemaName,
        ddl: sql,
      });

      if (!isPanel) {
        useGlobalStore.getState().setMainPageActiveTab({ page: 'workspace' });
      }
    },
    [isPanel, onPinSql],
  );

  // Render.

  const renderMarkdown = (content: string) => (
    <TableClickContext.Provider value={handleTableClick}>
      <PinSqlContext.Provider value={handlePinSql}>
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={{ code: MarkdownCodeBlock as React.ComponentType<React.HTMLAttributes<HTMLElement>> }}
        >
          {normalizeAiMarkdown(preprocessTableRefs(content))}
        </ReactMarkdown>
      </PinSqlContext.Provider>
    </TableClickContext.Provider>
  );

  const renderAssistantBadge = (active = false) => (
    <div
      className={cx(
        styles.assistantBadge,
        active && styles.assistantBadgeActive,
        active && styles.assistantBadgeLoading,
      )}
      aria-hidden="true"
    >
      {active && <span className={styles.assistantBadgeRing} />}
      <div className={styles.aiIconWrap}>
        <span className={styles.aiSpark}>✦</span>
      </div>
    </div>
  );

  const toggleTraceExpanded = useCallback((key: string) => {
    setExpandedTraceMap((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  }, []);

  const renderTraceEntry = (entry: ITraceEntry, index: number) => {
    if (entry.type === 'reasoning') {
      return (
        <div key={`${entry.type}-${index}`} className={styles.traceEntry}>
          <div className={styles.traceEntryTitle}>{getLatestMeaningfulLine(entry.content)}</div>
          {entry.content && <div className={styles.traceEntryText}>{entry.content}</div>}
        </div>
      );
    }

    if (entry.type === 'tool_call') {
      return (
        <div key={`${entry.type}-${entry.id || index}`} className={styles.traceEntry}>
          <div className={styles.traceEntryTag}>{i18n('stream.trace.toolCall')}</div>
          <div className={styles.traceEntryTitle}>{entry.name || i18n('stream.trace.unknownTool')}</div>
          {entry.arguments && <pre className={styles.traceCodeBlock}>{entry.arguments}</pre>}
        </div>
      );
    }

    if (entry.type === 'tool_result') {
      return (
        <div key={`${entry.type}-${index}`} className={styles.traceEntry}>
          <div className={styles.traceEntryTag}>{i18n('stream.trace.toolResult')}</div>
          <div className={styles.traceEntryTitle}>{entry.name || i18n('stream.trace.defaultToolResult')}</div>
          <pre className={styles.traceCodeBlock}>{entry.content}</pre>
        </div>
      );
    }

    return (
      <div key={`${entry.type}-${index}`} className={styles.traceEntry}>
        <div className={styles.traceEntryTag}>{i18n('stream.trace.error')}</div>
        <div className={styles.traceEntryText}>{entry.content || i18n('stream.trace.unknownError')}</div>
      </div>
    );
  };

  const renderThoughtStrip = (
    traceEntries: ITraceEntry[],
    traceKey: string,
    active = false,
    pulse = false,
  ) => {
    const hasEntries = traceEntries.length > 0;
    const expanded = !!expandedTraceMap[traceKey];
    const previewText = getTracePreview(traceEntries[traceEntries.length - 1]);
    const collapsedPreviewText = truncateCollapsedThoughtPreview(previewText);
    const buttonText = active ? collapsedPreviewText || i18n('stream.thought.toggle') : i18n('stream.thought.toggle');

    if (!active && !previewText) {
      return null;
    }

    return (
      <div className={cx(styles.thoughtWrap, active && styles.thoughtWrapActive)}>
        {active && renderAssistantBadge(true)}
        <div className={styles.thoughtMain}>
          <button className={styles.thoughtToggle} type="button" onClick={() => toggleTraceExpanded(traceKey)}>
            <span className={cx(styles.thoughtLabel, pulse && styles.thoughtPulse)} title={previewText || undefined}>
              {buttonText}
            </span>
            <span className={cx(styles.thoughtArrow, expanded && styles.thoughtArrowExpanded)}>⌃</span>
          </button>
          {expanded ? (
            <div className={styles.thoughtExpanded}>
              {!active && previewText ? <div className={styles.thoughtPreviewStatic}>{previewText}</div> : null}
              {hasEntries ? traceEntries.map((entry, index) => renderTraceEntry(entry, index)) : null}
            </div>
          ) : null}
        </div>
      </div>
    );
  };

  const renderMessages = () => {
    const rounds: IChatRound[] = [];
    let pendingRound: IChatRound | null = null;

    messages.forEach((item) => {
      if (item.role === 'user') {
        if (pendingRound) {
          rounds.push(pendingRound);
        }
        pendingRound = {
          key: item.id,
          user: item,
        };
        return;
      }

      if (pendingRound && !pendingRound.assistant) {
        pendingRound.assistant = item;
        rounds.push(pendingRound);
        pendingRound = null;
        return;
      }

      rounds.push({
        key: item.id,
        assistant: item,
      });
    });

    if (pendingRound) {
      rounds.push(pendingRound);
    }

    return (
      <>
        {rounds.map((round, index) => {
          const isCurrentRound = round.user?.id === currentRoundUserMessageId;
          const isLastRound = index === rounds.length - 1;

          return (
            <div
              key={round.key}
              className={styles.roundBlock}
              ref={isLastRound ? currentRoundBlockRef : null}
              style={isLastRound && messageListContentHeight > 0 ? { minHeight: messageListContentHeight } : undefined}
            >
              {round.user && (
                <div className={styles.userRow} ref={(node) => setMessageElement(round.user!.id, node)}>
                  <div className={styles.userBubbleWrap}>
                    {round.user.attachments?.length ? (
                      <div className={styles.userAttachmentList}>
                        {round.user.attachments.map((attachment, attachmentIndex) => (
                          <div
                            key={`${attachment.fileName}-${attachmentIndex}`}
                            className={styles.userAttachmentItem}
                            title={attachment.fileName}
                          >
                            {attachment.fileName}
                          </div>
                        ))}
                      </div>
                    ) : null}
                    <div className={styles.userBubble}>{round.user.content}</div>
                  </div>
                </div>
              )}
              {round.assistant && (
                <div className={styles.assistantRow}>
                  {renderAssistantBadge(false)}
                  <div className={styles.assistantContent}>
                    {renderThoughtStrip(round.assistant.traceEntries || [], `trace-${round.assistant.id}`)}
                    {renderMarkdown(round.assistant.content)}
                  </div>
                </div>
              )}
              {isCurrentRound &&
                renderThoughtStrip(
                  streamTraceEntries,
                  `stream-trace-${round.user?.id || round.key}`,
                  true,
                  streamThoughtPulse,
                )}
              {isCurrentRound &&
                streamingText &&
                (() => {
                  const { textBeforeChart, hasIncompleteChart } = splitIncompleteChartBlock(streamingText);
                  return (
                    <div className={styles.assistantRow}>
                      <div className={styles.assistantBadgeSpacer} aria-hidden="true" />
                      <div className={styles.assistantContent}>
                        {hasIncompleteChart ? (
                          <>
                            {textBeforeChart && renderMarkdown(textBeforeChart)}
                            <div className={styles.chartLoadingWrap}>
                              <div className={styles.chartLoadingIcon}>
                                <span className={styles.chartLoadingBar} />
                                <span className={styles.chartLoadingBar} />
                                <span className={styles.chartLoadingBar} />
                              </div>
                              <span className={styles.chartLoadingText}>{i18n('stream.chart.generating')}</span>
                            </div>
                          </>
                        ) : (
                          renderMarkdown(streamingText)
                        )}
                      </div>
                    </div>
                  );
                })()}
            </div>
          );
        })}
      </>
    );
  };

  // Panel-mode header.

  const renderPanelHeader = () => (
    <div className={styles.panelHeader}>
      <span className={styles.panelHeaderTitle}>{currentSessionTitle || i18n('stream.session.title')}</span>
      <Flex gap={4} align="center">
        <button
          className={styles.panelHeaderBtn}
          onClick={handleNewChat}
          title={`${i18n('stream.panel.newChat')} (${keyboardKey.command}+L)`}
        >
          <PlusOutlined />
        </button>
        <Dropdown
          trigger={['click']}
          onOpenChange={(open) => {
            if (open) fetchSessionList();
          }}
          menu={{
            items: sessionList.map((item) => ({
              key: item.id,
              label: (
                <div
                  className={styles.panelHistoryItem}
                  title={item.title || i18n('stream.session.title')}
                  onClick={() => handleLoadSessionById(item.id, item.title)}
                >
                  <span className={styles.panelHistoryTitle}>{item.title || i18n('stream.session.title')}</span>
                  <button
                    className={styles.panelHistoryDeleteBtn}
                    title={i18n('common.button.delete')}
                    onClick={(event) => {
                      event.preventDefault();
                      event.stopPropagation();
                      confirmDeleteHistorySession(item.id);
                    }}
                  >
                    <DeleteOutlined />
                  </button>
                </div>
              ),
            })),
            selectedKeys: currentSessionId ? [currentSessionId] : [],
            style: { maxHeight: 400, overflow: 'auto' },
          }}
          overlayStyle={{ maxWidth: 280 }}
          placement="bottomRight"
        >
          <button className={styles.panelHeaderBtn} title={i18n('stream.panel.history')}>
            <HistoryOutlined />
          </button>
        </Dropdown>
        <button
          className={styles.panelHeaderBtn}
          onClick={() => setShowPanel(false)}
          title={i18n('stream.panel.close')}
        >
          <CloseOutlined />
        </button>
      </Flex>
      {sessionLoading && <div className={styles.topLoadingBar} />}
    </div>
  );

  return (
    <div className={styles.main}>
      {isPanel
        ? renderPanelHeader()
        : (!isEmptyState || sessionLoading) && (
            <div className={styles.header}>
              {currentSessionTitle || i18n('stream.session.title')}
              {sessionLoading && <div className={styles.topLoadingBar} />}
            </div>
          )}

      {sessionLoading ? null : (
        <div className={isEmptyState && !isPanel ? styles.chatPanelCenter : styles.chatPanel}>
          {!isEmptyState && (
            <div className={styles.messageList} ref={messageListRef} onScroll={handleMessageListScroll}>
              <div className={styles.contentWidth} ref={messageContentRef}>
                {renderMessages()}
                <div ref={bottomSentinelRef} aria-hidden="true" />
              </div>
            </div>
          )}

          {isEmptyState && isPanel && (
            <div className={styles.panelWelcome}>
              <span className={styles.panelWelcomeSpark}>✦</span>
              <div className={styles.panelWelcomeText}>{i18n('stream.welcome.title')}</div>
            </div>
          )}

          <div
            className={
              isEmptyState && !isPanel
                ? styles.inputWrapCenter
                : isEmptyState && isPanel
                ? styles.inputWrapBottom
                : styles.inputWrap
            }
          >
            {isEmptyState && !isPanel && (
              <div className={`${styles.welcome} ${styles.contentWidth}`}>
                <div className={styles.welcomeGreeting}>
                  <span className={styles.welcomeSpark}>✦</span>
                  <span>{i18n('stream.welcome.greeting')}</span>
                </div>
                <div className={styles.welcomeTitle}>{i18n('stream.welcome.title')}</div>
              </div>
            )}
            <div className={styles.contentWidth}>
              <AIChatInput
                ref={chatInputRef}
                className={styles.chatInput}
                chatInputAreaClassName={`${styles.chatInputAreaRounded} ${
                  status === SSERequestStatus.LOADING ? styles.chatInputAreaLoading : ''
                }`}
                loading={status === SSERequestStatus.LOADING}
                onContextChange={() => {
                  handleNewChat();
                }}
                onChatSend={handleSend}
                onStop={stop}
                autoSize={
                  isPanel
                    ? { minRows: 2, maxRows: 4 }
                    : isEmptyState
                    ? { minRows: 3, maxRows: 8 }
                    : { minRows: 2, maxRows: 6 }
                }
                modelOptions={modelOptions}
                showCustomModelEntry
                onCustomModelClick={() => setOpenSettings(true)}
                customModelText={i18n('setting.modelConfig.entry')}
                prefillInputState={prefillInputState}
                autoFocus={isDesktop}
              />
            </div>
          </div>
        </div>
      )}
      <AIModelConfigModal
        open={openSettings}
        onClose={() => setOpenSettings(false)}
        onChanged={loadModelOptions}
      />
    </div>
  );
}
