import i18n from '@/i18n';
import {
  deleteConnectorSession,
  listConnectorSessions,
  listConnectorConversations,
  revokeConnectorSession,
  type AgentConnectorConversation,
  type AgentConnectorSession,
} from '@/service/agentConnector';
import { App, Button, Empty, Popconfirm, Skeleton, Space, Table, Tag, Tooltip } from 'antd';
import { createStyles } from 'antd-style';
import dayjs from 'dayjs';
import { AlertTriangle, Cable, Eye, RefreshCw, Trash2, Unplug } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';

interface Props {
  active: boolean;
  onOpenTask: (taskId: string) => void;
}

const useConnectorStyles = createStyles(({ css, token }) => ({
  page: css`
    display: flex;
    height: 100%;
    min-height: 0;
    flex-direction: column;
    background: ${token.colorBgLayout};
  `,
  header: css`
    display: flex;
    min-height: 56px;
    align-items: center;
    justify-content: space-between;
    padding: 0 20px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgContainer};
  `,
  title: css`
    display: flex;
    align-items: center;
    gap: 10px;
    h1 { margin: 0; font-size: 17px; font-weight: 600; }
    p { margin: 2px 0 0; color: ${token.colorTextTertiary}; font-size: 12px; }
  `,
  icon: css`
    display: grid;
    width: 32px;
    height: 32px;
    place-items: center;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    color: ${token.colorPrimary};
  `,
  body: css`
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 16px;
  `,
  pendingRow: css`
    > td { background: ${token.colorWarningBg} !important; }
  `,
}));

function time(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

export default function ConnectorSessionsPage({ active, onOpenTask }: Props) {
  const { styles } = useConnectorStyles();
  const { message } = App.useApp();
  const [sessions, setSessions] = useState<AgentConnectorSession[]>([]);
  const [loading, setLoading] = useState(false);
  const [stopping, setStopping] = useState<string>();
  const [deleting, setDeleting] = useState<string>();
  const [conversations, setConversations] = useState<Record<string, AgentConnectorConversation[]>>({});
  const [conversationLoading, setConversationLoading] = useState<string>();
  const [expandedSessionIds, setExpandedSessionIds] = useState<string[]>([]);

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      setSessions(await listConnectorSessions());
    } catch {
      if (!silent) message.error(i18n('task.connector.loadFailed'));
    } finally {
      if (!silent) setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    if (!active) return undefined;
    void load();
    const timer = window.setInterval(() => void load(true), 3000);
    return () => window.clearInterval(timer);
  }, [active, load]);

  const stop = async (session: AgentConnectorSession) => {
    setStopping(session.sessionId);
    try {
      const updated = await revokeConnectorSession(session.sessionId);
      setSessions((current) => current.map((item) => item.sessionId === updated.sessionId ? updated : item));
      message.success(i18n('task.connector.stopSuccess'));
    } catch {
      message.error(i18n('task.connector.stopFailed'));
    } finally {
      setStopping(undefined);
    }
  };

  const remove = async (session: AgentConnectorSession) => {
    setDeleting(session.sessionId);
    try {
      await deleteConnectorSession(session.sessionId);
      setSessions((current) => current.filter((item) => item.sessionId !== session.sessionId));
      setConversations((current) => {
        const next = { ...current };
        delete next[session.sessionId];
        return next;
      });
      setExpandedSessionIds((current) => current.filter((id) => id !== session.sessionId));
      message.success(i18n('task.connector.deleteSuccess'));
    } catch {
      message.error(i18n('task.connector.deleteFailed'));
    } finally {
      setDeleting(undefined);
    }
  };

  const loadConversations = useCallback(async (sessionId: string, silent = false) => {
    if (!silent) setConversationLoading(sessionId);
    try {
      const items = await listConnectorConversations(sessionId);
      setConversations((current) => ({ ...current, [sessionId]: items }));
    } catch {
      if (!silent) message.error(i18n('task.connector.conversationLoadFailed'));
    } finally {
      if (!silent) setConversationLoading(undefined);
    }
  }, [message]);

  useEffect(() => {
    if (!active || !expandedSessionIds.length) return undefined;
    const refresh = () => expandedSessionIds.forEach((sessionId) => void loadConversations(sessionId, true));
    refresh();
    const timer = window.setInterval(refresh, 2000);
    return () => window.clearInterval(timer);
  }, [active, expandedSessionIds, loadConversations]);

  return (
    <section className={styles.page}>
      <header className={styles.header}>
        <div className={styles.title}>
          <span className={styles.icon}><Cable size={17} /></span>
          <div>
            <h1>{i18n('task.connector.title')}</h1>
            <p>{i18n('task.connector.hint')}</p>
          </div>
        </div>
        <Tooltip title={i18n('task.action.refresh')}>
          <Button icon={<RefreshCw size={15} />} aria-label={i18n('task.action.refresh')} onClick={() => void load()} />
        </Tooltip>
      </header>
      <div className={styles.body}>
        {loading && !sessions.length ? <Skeleton active /> : sessions.length ? (
          <Table
            rowKey="sessionId"
            dataSource={sessions}
            rowClassName={(session) => session.pendingApprovalCount > 0 ? styles.pendingRow : ''}
            pagination={{ pageSize: 20, hideOnSinglePage: true }}
            expandable={{
              rowExpandable: (session) => session.conversationCount > 0 || session.legacyAudit,
              onExpand: (expanded, session) => {
                setExpandedSessionIds((current) => expanded
                  ? [...new Set([...current, session.sessionId])]
                  : current.filter((id) => id !== session.sessionId));
                if (expanded && session.conversationCount > 0) void loadConversations(session.sessionId);
              },
              expandedRowRender: (session) => (
                <Table
                  size="small"
                  rowKey="conversationId"
                  loading={conversationLoading === session.sessionId}
                  pagination={false}
                  dataSource={conversations[session.sessionId] || []}
                  rowClassName={(conversation) => conversation.pendingApprovalCount > 0 ? styles.pendingRow : ''}
                  locale={{ emptyText: session.legacyAudit
                    ? i18n('task.connector.legacyAudit')
                    : i18n('task.connector.conversationEmpty') }}
                  columns={[
                    { title: i18n('task.connector.conversation'), dataIndex: 'externalSessionId', ellipsis: true },
                    {
                      title: i18n('task.connector.status'), dataIndex: 'status', width: 210,
                      render: (status: AgentConnectorConversation['status'], item: AgentConnectorConversation) => (
                        <Space size={4} wrap>
                          <Tag color={status === 'active' ? 'processing' : 'default'}>
                            {i18n(`task.connector.conversationStatus.${status}` as Parameters<typeof i18n>[0])}
                          </Tag>
                          {item.pendingApprovalCount > 0 ? (
                            <Tag icon={<AlertTriangle size={12} />} color="warning">
                              {i18n('task.connector.pendingApproval', item.pendingApprovalCount)}
                            </Tag>
                          ) : null}
                        </Space>
                      ),
                    },
                    { title: i18n('task.connector.lastUsedAt'), dataIndex: 'lastUsedAt', render: time },
                    {
                      title: i18n('task.connector.operation'), width: 90,
                      render: (_: unknown, item: AgentConnectorConversation) => (
                        <Tooltip title={i18n('task.connector.auditDetail')}>
                          <Button
                            icon={<Eye size={15} />}
                            aria-label={i18n('task.connector.auditDetail')}
                            onClick={() => onOpenTask(item.taskId)}
                          />
                        </Tooltip>
                      ),
                    },
                  ]}
                />
              ),
            }}
            columns={[
              { title: i18n('task.connector.client'), dataIndex: 'clientName' },
              { title: i18n('task.connector.agent'), dataIndex: 'agentName' },
              {
                title: i18n('task.connector.status'), dataIndex: 'status', width: 210,
                render: (status: AgentConnectorSession['status'], session: AgentConnectorSession) => (
                  <Space size={4} wrap>
                    <Tag color={status === 'active' ? 'processing' : status === 'expired' ? 'warning' : 'default'}>
                      {i18n(`task.connector.status.${status}` as Parameters<typeof i18n>[0])}
                    </Tag>
                    {session.pendingApprovalCount > 0 ? (
                      <Tag icon={<AlertTriangle size={12} />} color="warning">
                        {i18n('task.connector.pendingApproval', session.pendingApprovalCount)}
                      </Tag>
                    ) : null}
                  </Space>
                ),
              },
              { title: i18n('task.connector.lastUsedAt'), dataIndex: 'lastUsedAt', render: time },
              { title: i18n('task.connector.createdAt'), dataIndex: 'createdAt', render: time },
              {
                title: i18n('task.connector.operation'), width: 130,
                render: (_: unknown, session: AgentConnectorSession) => (
                  <Space>
                    {session.legacyAudit && session.taskId ? (
                      <Tooltip title={i18n('task.connector.auditDetail')}>
                        <Button
                          icon={<Eye size={15} />}
                          aria-label={i18n('task.connector.auditDetail')}
                          onClick={() => onOpenTask(session.taskId!)}
                        />
                      </Tooltip>
                    ) : null}
                    {session.status === 'active' ? (
                      <Popconfirm
                        title={i18n('task.connector.stopConfirm')}
                        onConfirm={() => void stop(session)}
                      >
                        <Tooltip title={i18n('task.connector.stop')}>
                          <Button
                            danger
                            icon={<Unplug size={15} />}
                            aria-label={i18n('task.connector.stop')}
                            loading={stopping === session.sessionId}
                          />
                        </Tooltip>
                      </Popconfirm>
                    ) : (
                      <Popconfirm
                        title={i18n('task.connector.deleteConfirm')}
                        onConfirm={() => void remove(session)}
                      >
                        <Tooltip title={i18n('task.connector.delete')}>
                          <Button
                            danger
                            icon={<Trash2 size={15} />}
                            aria-label={i18n('task.connector.delete')}
                            loading={deleting === session.sessionId}
                          />
                        </Tooltip>
                      </Popconfirm>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        ) : <Empty description={i18n('task.connector.empty')} />}
      </div>
    </section>
  );
}
