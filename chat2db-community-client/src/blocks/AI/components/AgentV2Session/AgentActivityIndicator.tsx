import { createStyles } from 'antd-style';
import i18n from '@/i18n';
import type { AgentActivity } from './presentation';

const useStyles = createStyles(({ css, token }) => ({
  activity: css`
    display: inline-flex;
    min-width: 0;
    max-width: 100%;
    color: ${token.colorPrimary};
    font-size: 12px;
    line-height: 22px;
  `,
  label: css`
    min-width: 0;
    overflow-wrap: anywhere;
    animation: agentTextPulse 1.6s ease-in-out infinite;
    @keyframes agentTextPulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.55; } }
    @media (prefers-reduced-motion: reduce) { animation: none; }
  `,
}));

export default function AgentActivityIndicator({ activity }: { activity: AgentActivity }) {
  const { styles } = useStyles();
  const label = activity.kind === 'question' ? i18n('stream.question.pending')
    : activity.kind === 'approval' ? i18n('stream.approval.pending')
    : activity.kind === 'cancelling' ? i18n('stream.activity.cancelling')
    : activity.kind === 'tool' ? (activity.tool.description || activity.tool.name)
    : i18n('stream.activity.starting');
  return (
    <span className={styles.activity} role="status" data-agent-activity={activity.kind}>
      <span className={styles.label} title={label}>{label}</span>
    </span>
  );
}
