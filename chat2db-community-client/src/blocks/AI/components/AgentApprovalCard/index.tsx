import { useState } from 'react';
import { Button, Dropdown } from 'antd';
import { ChevronDown, Database, Terminal } from 'lucide-react';
import i18n from '@/i18n';
import { AgentApprovalDecision, AgentApprovalItem, agentErrorText } from '../../agentEvents';
import { useStyles } from './style';

export default function AgentApprovalCard({ approval, onDecide }: {
  approval: AgentApprovalItem;
  onDecide: (decision: AgentApprovalDecision) => Promise<void>;
}) {
  const { styles } = useStyles();
  const [submitting, setSubmitting] = useState<'approve' | 'deny' | null>(null);
  const [error, setError] = useState('');
  const pending = approval.status === 'pending';
  const target = approval.databaseTarget;
  const decide = async (decision: AgentApprovalDecision) => {
    if (submitting || !pending) return;
    setSubmitting(decision === 'DENY' ? 'deny' : 'approve');
    setError('');
    try {
      await onDecide(decision);
    } catch (failure) {
      setError(agentErrorText(failure) || i18n('stream.agent.sendFailed'));
    } finally {
      setSubmitting(null);
    }
  };

  return <section className={styles.card} aria-label={i18n('stream.approval.title')}>
    <div className={styles.header}>
      {target ? <Database size={16} aria-hidden="true" /> : <Terminal size={16} aria-hidden="true" />}
      <strong>{approval.toolName}</strong>
      <span className={styles.status} role="status">{i18n(`stream.approval.${approval.status}`)}</span>
    </div>
    {target && <div className={styles.directory}>
      <span>{i18n('stream.approval.datasource')}</span>
      <code>{target.dataSourceName} ({target.dataSourceId})</code>
      {target.database && <><span>{i18n('stream.approval.database')}</span><code>{target.database}</code></>}
      {target.schema && <><span>{i18n('stream.approval.schema')}</span><code>{target.schema}</code></>}
    </div>}
    {approval.workingDirectory && <div className={styles.directory}>
      <span>{i18n('setting.agent.workingDirectory')}</span>
      <code>{approval.workingDirectory}</code>
    </div>}
    <pre className={styles.command} tabIndex={0} aria-label={target ? 'SQL' : i18n('stream.approval.command')}>
      <code>{approval.command}</code>
    </pre>
    {pending && <>
      <div className={styles.footer}>
        <span>{i18n('stream.approval.hint')}</span>
        <div className={styles.actions}>
          <Button size="small" disabled={!!submitting} loading={submitting === 'deny'}
            onClick={() => void decide('DENY')}
          >{i18n('stream.approval.deny')}</Button>
          <Button size="small" type="primary" disabled={!!submitting} loading={submitting === 'approve'}
            onClick={() => void decide('ALLOW_ONCE')}
          >{i18n('stream.approval.approve')}</Button>
          <Dropdown
            trigger={['click']}
            disabled={!!submitting}
            menu={{
              items: [
                { key: 'ALLOW_TOOL', label: i18n('stream.approval.allowTool') },
                { key: 'ALLOW_SERVER', label: i18n('stream.approval.allowServer') },
              ],
              onClick: ({ key }) => void decide(key as AgentApprovalDecision),
            }}
          >
            <Button size="small" disabled={!!submitting} aria-label={i18n('stream.approval.allowMore')}>
              <ChevronDown size={14} />
            </Button>
          </Dropdown>
        </div>
      </div>
      {error && <div className={styles.error} role="alert">{error}</div>}
    </>}
  </section>;
}
