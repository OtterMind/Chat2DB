import { createStyles } from 'antd-style';
import i18n from '@/i18n';
import type { AgentTraceEntry } from '../../agentEvents';

const useStyles = createStyles(({ css, token }) => ({
  group: css`
    margin: 8px 0 12px;
    color: ${token.colorTextSecondary};
    summary { cursor: pointer; font-size: 12px; }
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
  reasoning: css`white-space: pre-wrap; overflow-wrap: anywhere;`,
  failed: css`color: ${token.colorError};`,
}));

const formatJson = (value: string) => {
  try { return JSON.stringify(JSON.parse(value), null, 2); }
  catch { return value; }
};

export default function AgentTraceGroup({ entries }: { entries: AgentTraceEntry[] }) {
  const { styles } = useStyles();
  const failed = entries.some((entry) => entry.failed);
  return (
    <details className={styles.group}>
      <summary className={failed ? styles.failed : undefined}>
        {i18n('stream.thought.toggle')}{failed ? ` · ${i18n('stream.trace.error')}` : ''}
      </summary>
      {entries.map((entry, index) => (
        <div key={`${entry.id || entry.type}-${index}`} className={styles.trace}>
          {entry.type === 'reasoning'
            ? <div className={styles.reasoning}>{entry.content}</div>
            : <>
              <div className={entry.failed ? styles.failed : styles.label}>
                {i18n(entry.type === 'tool_call' ? 'stream.trace.toolCall' : 'stream.trace.toolResult')}
                {entry.name && ` · ${entry.name}`}
              </div>
              <pre className={styles.code} tabIndex={0}>{formatJson(entry.arguments || entry.content || '')}</pre>
            </>}
        </div>
      ))}
    </details>
  );
}
