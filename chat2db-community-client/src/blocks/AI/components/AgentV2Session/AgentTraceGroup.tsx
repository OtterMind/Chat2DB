import { createStyles } from 'antd-style';
import i18n from '@/i18n';
import type { AgentTraceEntry } from '../../agentEvents';
import { Check, ChevronRight, CircleX, Clock3, LoaderCircle, Wrench } from 'lucide-react';
import AgentActivityIndicator from './AgentActivityIndicator';
import { toolExecutions, toolSummary, type AgentActivity } from './presentation';

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
  trace: css`margin: 10px 0; padding-left: 12px; border-left: 2px solid ${token.colorBorderSecondary};`,
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
    margin: 6px 0;
    > summary { width: 100%; margin: 0; padding: 6px 0; }
  `,
  toolText: css`flex: 1; min-width: 0; overflow-wrap: anywhere;`,
  duration: css`flex-shrink: 0; font-variant-numeric: tabular-nums;`,
  spinner: css`
    animation: toolSpin 1s linear infinite;
    @keyframes toolSpin { to { transform: rotate(360deg); } }
    @media (prefers-reduced-motion: reduce) { animation: none; }
  `,
}));

const formatJson = (value: string) => {
  try { return JSON.stringify(JSON.parse(value), null, 2); }
  catch { return value; }
};

export default function AgentTraceGroup({ entries, activity, status, onInspect }: {
  entries: AgentTraceEntry[]; activity?: AgentActivity; status?: 'failed' | 'cancelled' | 'unknown';
  onInspect?: () => void;
}) {
  const { styles } = useStyles();
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
        {activity ? <AgentActivityIndicator activity={activity} /> : <>
          <Wrench size={14} aria-hidden="true" />{title}
          {outcome && ` · ${outcome}`}
          {!outcome && failed && ` · ${i18n('stream.trace.error')}`}
        </>}
        <ChevronRight size={13} className="agent-trace-chevron" aria-hidden="true" />
      </summary>
      {tools.map((tool) => {
        const state = tool.failed ? 'failed' : tool.completed ? 'completed' : activity ? 'running' : 'stopped';
        return <details key={tool.id} className={styles.tool} data-agent-tool={tool.id}>
          <summary className={tool.failed ? styles.failed : undefined}>
            {state === 'failed' ? <CircleX size={14} aria-hidden="true" />
              : state === 'completed' ? <Check size={14} aria-hidden="true" />
              : state === 'running' ? <LoaderCircle size={14} className={styles.spinner} aria-hidden="true" />
              : <Clock3 size={14} aria-hidden="true" />}
            <span className={styles.toolText}>{tool.description || tool.name || i18n('stream.trace.unknownTool')}</span>
            <span className={styles.duration}>{i18n(`stream.tool.${state}`)}
              {tool.durationMs !== undefined && ` · ${tool.durationMs}ms`}
            </span>
          </summary>
          <div className={styles.trace}>
            <div className={styles.label}>{tool.name} · {i18n('stream.trace.toolCall')}</div>
            <pre className={styles.code} tabIndex={0}>{formatJson(tool.arguments || '{}')}</pre>
            {tool.completed && <>
              <div className={styles.label}>{i18n('stream.trace.toolResult')}
                {tool.durationMs !== undefined && ` · ${i18n('stream.trace.duration', tool.durationMs)}`}
              </div>
              <pre className={styles.code} tabIndex={0}>{formatJson(tool.content || '')}</pre>
            </>}
          </div>
        </details>;
      })}
    </details>
  );
}
