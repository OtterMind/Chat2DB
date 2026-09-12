import { CircleHelp, LoaderCircle, ShieldQuestion } from 'lucide-react';
import { createStyles } from 'antd-style';
import i18n from '@/i18n';
import type { AgentActivity } from './presentation';

const useStyles = createStyles(({ css, token }) => ({
  activity: css`
    display: inline-flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
    max-width: 100%;
    color: ${token.colorPrimary};
    font-size: 12px;
    line-height: 22px;
    svg { flex-shrink: 0; }
  `,
  label: css`overflow: hidden; text-overflow: ellipsis; white-space: nowrap;`,
  spinner: css`
    animation: agentActivitySpin 1s linear infinite;
    @keyframes agentActivitySpin { to { transform: rotate(360deg); } }
    @media (prefers-reduced-motion: reduce) { animation: none; }
  `,
}));

export default function AgentActivityIndicator({ activity }: { activity: AgentActivity }) {
  const { styles } = useStyles();
  const label = activity.kind === 'question' ? i18n('stream.question.pending')
    : activity.kind === 'approval' ? i18n('stream.approval.pending')
    : activity.kind === 'responding' ? i18n('stream.activity.responding')
    : activity.kind === 'tool' ? (activity.tool.description || activity.tool.name)
    : i18n('stream.loading.thinking');
  return (
    <span className={styles.activity} role="status" data-agent-activity={activity.kind}>
      {activity.kind === 'question' ? <CircleHelp size={14} aria-hidden="true" />
        : activity.kind === 'approval' ? <ShieldQuestion size={14} aria-hidden="true" />
        : <LoaderCircle size={14} className={styles.spinner} aria-hidden="true" data-agent-spinner />}
      <span className={styles.label} title={label}>{label}</span>
    </span>
  );
}
