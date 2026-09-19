import { createStyles } from 'antd-style';
import { useEffect, useState } from 'react';
import i18n from '@/i18n';
import type { AgentTraceEntry } from '../../agentEvents';
import { Check, ChevronRight, CircleX, Clock3, Wrench } from 'lucide-react';
import AgentActivityIndicator from './AgentActivityIndicator';
import AgentToolOutput from './AgentToolOutput';
import { formatToolResult } from '../../agentOutput';
import { toolExecutions, toolSummary, type AgentActivity } from './presentation';

const THINKING_DELAY_MS = 600;

const useStyles = createStyles(({ css, token }) => ({
  group: css`
    margin: 8px 0 12px;
    color: ${token.colorTextSecondary};
    summary {
      display: flex;
      align-items: center;
      gap: 7px;
      width: fit-content;
      max-width: 100%;
      padding: 3px 8px;
      margin-left: -8px;
      border-radius: 6px;
      cursor: pointer;
      font-size: 12px;
      line-height: 22px;
      list-style: none;
      &::-webkit-details-marker { display: none; }
      &:hover { background: ${token.colorFillTertiary}; color: ${token.colorText}; }
      &:focus-visible { outline: 2px solid ${token.colorPrimary}; }
      > svg { flex-shrink: 0; }
    }
    &[open] .agent-trace-chevron { transform: rotate(90deg); }
  `,
  trace: css`
    margin: 6px 0 8px;
    padding-left: 10px;
    border-left: 2px solid ${token.colorBorderSecondary};
  `,
  label: css`font-size: 12px; font-weight: 600; margin-bottom: 4px;`,
  code: css`
    margin: 6px 0 0;
    padding: 10px 12px;
    max-height: 280px;
    overflow: auto;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 6px;
    background: ${token.colorFillTertiary};
    font: 12px/1.6 monospace;
    white-space: pre;
  `,
  failed: css`color: ${token.colorError};`,
  tool: css`
    margin-left: 21px;
    > summary {
      display: grid;
      grid-template-columns: 14px minmax(0, 1fr) 8ch;
      align-items: start;
      width: 100%;
      margin: 0;
      padding: 2px 0;
      line-height: 20px;
    }
  `,
  toolIcon: css`display: inline-flex; margin-top: 3px;`,
  toolText: css`min-width: 0; overflow-wrap: anywhere;`,
  duration: css`
    min-width: 0;
    overflow-wrap: anywhere;
    text-align: right;
    font-variant-numeric: tabular-nums;
  `,
}));

export default function AgentTraceGroup({ entries, activity, status, runActive = false, onInspect, sessionId }: {
  sessionId?: string;
  entries: AgentTraceEntry[]; activity?: AgentActivity; status?: 'failed' | 'cancelled' | 'unknown';
  runActive?: boolean;
  onInspect?: () => void;
}) {
  const { styles } = useStyles();
  const [showActivity, setShowActivity] = useState(activity?.kind !== 'starting');
  const activityKind = activity?.kind;
  const activityName = activity?.kind === 'tool' ? activity.tool.name : undefined;
  const activityDescription = activity?.kind === 'tool' ? activity.tool.description : undefined;
  useEffect(() => {
    if (!activityKind || activityKind !== 'starting' || !runActive) {
      setShowActivity(!!activityKind);
      return undefined;
    }
    setShowActivity(false);
    const timer = window.setTimeout(() => setShowActivity(true), THINKING_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [activityKind, activityName, activityDescription, runActive]);
  const tools = toolExecutions(entries);
  const failed = tools.some((tool) => tool.failed) || status === 'failed' || status === 'unknown';
  const summary = toolSummary(entries);
  const outcome = status ? i18n(`stream.agent.status.${status}`) : '';
  if (!summary) return <div data-agent-progress>
    {activity ? <AgentActivityIndicator activity={activity} /> : outcome && <span role="status">{outcome}</span>}
  </div>;
  const title = summary.durationMs === undefined
    ? i18n('stream.trace.toolsCount', summary.count)
    : i18n('stream.trace.toolsSummary', summary.count, summary.durationMs);
  return (
    <details className={styles.group} data-agent-progress onClickCapture={(event) => {
      if (event.target instanceof Element && event.target.closest('summary')) onInspect?.();
    }}
    >
      <summary className={failed ? styles.failed : undefined}>
        <Wrench size={14} aria-hidden="true" />{title}
        {showActivity && activity && <> · <AgentActivityIndicator activity={activity} /></>}
        {outcome && ` · ${outcome}`}
        {!outcome && failed && ` · ${i18n('stream.trace.error')}`}
        <ChevronRight size={13} className="agent-trace-chevron" aria-hidden="true" />
      </summary>
      {tools.map((tool) => {
        const state = tool.failed ? 'failed' : tool.completed ? 'completed' : runActive ? 'running' : 'stopped';
        const stateLabel = i18n(`stream.tool.${state}`);
        return <details key={tool.id} className={styles.tool} data-agent-tool={tool.id}>
          <summary className={tool.failed ? styles.failed : undefined}>
            <span className={styles.toolIcon} role="img" aria-label={stateLabel} title={stateLabel}>
              {state === 'failed' ? <CircleX size={14} aria-hidden="true" />
                : state === 'completed' ? <Check size={14} aria-hidden="true" />
                : state === 'running' ? <Wrench size={14} aria-hidden="true" />
                : <Clock3 size={14} aria-hidden="true" />}
            </span>
            <span className={styles.toolText}>
              {state === 'running' ? <AgentActivityIndicator activity={{ kind: 'tool', tool }} />
                : tool.description || tool.name || i18n('stream.trace.unknownTool')}
            </span>
            <span className={styles.duration}>
              {tool.durationMs !== undefined && `${tool.durationMs}ms`}
            </span>
          </summary>
          <div className={styles.trace}>
            <div className={styles.label}>{tool.name} · {i18n('stream.trace.toolCall')}</div>
            <pre className={styles.code} tabIndex={0}>{formatToolResult(tool.arguments || '{}')}</pre>
            {tool.completed && <>
              <div className={styles.label}>{i18n('stream.trace.toolResult')}
                {tool.durationMs !== undefined && ` · ${i18n('stream.trace.duration', tool.durationMs)}`}
              </div>
              {tool.outputs?.map(({ output, resultIndex }) => <AgentToolOutput
                key={`${sessionId}:${output.mode === 'file' ? output.artifactId : tool.id}:${resultIndex ?? ''}`}
                sessionId={sessionId} output={output} resultIndex={resultIndex}
                                                              />)}
              <pre className={styles.code} tabIndex={0}>{formatToolResult(tool.content || '')}</pre>
            </>}
          </div>
        </details>;
      })}
    </details>
  );
}
