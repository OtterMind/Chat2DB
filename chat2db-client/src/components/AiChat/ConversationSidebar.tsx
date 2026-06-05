import React, { memo, useEffect, useState, useRef } from 'react';
import { Button, Input, Modal, Dropdown, message, Spin } from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  EditOutlined,
  MessageOutlined,
  MoreOutlined,
} from '@ant-design/icons';
import i18n from '@/i18n';
import { useAiChatStore, AiChatSession } from '@/pages/main/workspace/store/aiChatStore';
import styles from './index.less';

interface IConversationItemProps {
  session: AiChatSession;
  active: boolean;
  onClick: () => void;
  onDelete: () => void;
  onRename: (title: string) => void;
}

const ConversationItem = memo<IConversationItemProps>(({ session, active, onClick, onDelete, onRename }) => {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(session.title || '');
  const inputRef = useRef<any>(null);

  useEffect(() => {
    if (editing) {
      inputRef.current?.focus();
      inputRef.current?.select?.();
    }
  }, [editing]);

  const handleSubmit = () => {
    const next = draft.trim();
    if (next && next !== session.title) {
      onRename(next);
    }
    setEditing(false);
  };

  const displayTitle = session.title || (session.messages[0]?.content || i18n('chat.header.titleFallback')).slice(0, 20);

  return (
    <div
      className={`${styles.conversationItem} ${active ? styles.conversationItemActive : ''}`}
      onClick={editing ? undefined : onClick}
    >
      <MessageOutlined className={styles.conversationItemIcon} />
      {editing ? (
        <Input
          ref={inputRef}
          size="small"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onPressEnter={handleSubmit}
          onBlur={handleSubmit}
          onClick={(e) => e.stopPropagation()}
          className={styles.conversationItemInput}
        />
      ) : (
        <span className={styles.conversationItemTitle} title={displayTitle}>
          {displayTitle}
        </span>
      )}
      <Dropdown
        menu={{
          items: [
            {
              key: 'rename',
              label: i18n('chat.sidebar.rename'),
              icon: <EditOutlined />,
              onClick: ({ domEvent }) => {
                domEvent.stopPropagation();
                setEditing(true);
              },
            },
            {
              key: 'delete',
              label: i18n('chat.sidebar.delete'),
              icon: <DeleteOutlined />,
              danger: true,
              onClick: ({ domEvent }) => {
                domEvent.stopPropagation();
                onDelete();
              },
            },
          ],
        }}
        trigger={['click']}
        placement="bottomRight"
      >
        <MoreOutlined
          className={styles.conversationItemMore}
          onClick={(e) => e.stopPropagation()}
        />
      </Dropdown>
    </div>
  );
});
ConversationItem.displayName = 'ConversationItem';

export default memo(() => {
  const {
    conversationList,
    conversationListLoading,
    conversationListHasMore,
    sessions,
    currentSessionId,
    startNewConversation,
    switchToSession,
    deleteSession,
    renameSession,
    loadConversationList,
  } = useAiChatStore();

  useEffect(() => {
    loadConversationList(true);
  }, [loadConversationList]);

  const handleNew = () => {
    startNewConversation();
  };

  const handleDelete = (conversationId: string) => {
    Modal.confirm({
      title: i18n('chat.sidebar.delete.confirm'),
      okText: i18n('chat.sidebar.delete'),
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => deleteSession(conversationId).catch(() => message.error('删除失败')),
    });
  };

  const handleRename = (conversationId: string, title: string) => {
    renameSession(conversationId, title).catch(() => message.error('重命名失败'));
  };

  return (
    <div className={styles.conversationSidebar}>
      <div className={styles.sidebarHeader}>
        <span className={styles.sidebarTitle}>{i18n('chat.sidebar.title')}</span>
        <Button
          type="text"
          size="small"
          icon={<PlusOutlined />}
          onClick={handleNew}
          title={i18n('chat.sidebar.new')}
        />
      </div>
      <div className={styles.sidebarList}>
        {conversationList.length === 0 && !conversationListLoading ? (
          <div className={styles.sidebarEmpty}>{i18n('chat.sidebar.empty')}</div>
        ) : (
          conversationList.map((item) => {
            const localSession = sessions[item.conversationId];
            const sessionForRender: AiChatSession =
              localSession ||
              ({
                sessionId: item.conversationId,
                state: 'COMPLETED',
                messages: item.lastMessagePreview
                  ? [{ id: 'preview', role: 'assistant' as const, content: item.lastMessagePreview }]
                  : [],
                currentContent: '',
                currentThinking: '',
                title: item.title,
                dataSourceId: item.dataSourceId,
                databaseName: item.databaseName,
                schemaName: item.schemaName,
                createdAt: 0,
                updatedAt: 0,
              } as AiChatSession);
            return (
              <ConversationItem
                key={item.conversationId}
                session={sessionForRender}
                active={currentSessionId === item.conversationId}
                onClick={() => switchToSession(item.conversationId)}
                onDelete={() => handleDelete(item.conversationId)}
                onRename={(title) => handleRename(item.conversationId, title)}
              />
            );
          })
        )}
        {conversationListLoading && (
          <div className={styles.sidebarLoading}>
            <Spin size="small" />
          </div>
        )}
        {conversationListHasMore && !conversationListLoading && (
          <div
            className={styles.sidebarLoadMore}
            onClick={() => loadConversationList(false)}
          >
            加载更多
          </div>
        )}
      </div>
    </div>
  );
});
