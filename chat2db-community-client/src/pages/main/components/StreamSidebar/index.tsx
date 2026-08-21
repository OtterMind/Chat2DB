import React, { useCallback, useMemo, useState } from 'react';
import { IconButton, IconfontSvg } from '@chat2db/ui';
import { Button, Input, Modal, Tooltip, type InputRef } from 'antd';
import dayjs from 'dayjs';
import { MessageSquarePlus, Search } from 'lucide-react';

import PortalContextMenu from '@/components/ContextMenu/PortalContextMenu';
import type { ContextMenuAction, ContextMenuIntent } from '@/components/ContextMenu/core';
import i18n from '@/i18n';
import { IChatSession } from '@/service/aiStream';
import MainSecondaryPanel from '../MainSecondaryPanel';
import { useStyles } from './style';

interface StreamSidebarProps {
  sessions: IChatSession[];
  activeSessionId: string | null;
  searchOpen: boolean;
  searchKeyword: string;
  searchInputRef: React.RefObject<InputRef>;
  onSearchOpenChange: (open: boolean) => void;
  onSearchKeywordChange: (keyword: string) => void;
  onSearchBlur: () => void;
  onNewChat: () => void;
  onSessionClick: (session: IChatSession) => void;
  onSessionDelete: (sessionId: string) => void;
}

interface StreamSessionContextSnapshot {
  sessionId: string;
  title?: string;
}

type StreamSessionContextIntent = ContextMenuIntent<StreamSessionContextSnapshot>;

const StreamSidebar = ({
  sessions,
  activeSessionId,
  searchOpen,
  searchKeyword,
  searchInputRef,
  onSearchOpenChange,
  onSearchKeywordChange,
  onSearchBlur,
  onNewChat,
  onSessionClick,
  onSessionDelete,
}: StreamSidebarProps) => {
  const { styles } = useStyles();
  const [contextMenu, setContextMenu] = useState<StreamSessionContextIntent | null>(null);
  const [modal, modalContextHolder] = Modal.useModal();

  const formatSessionTime = useCallback((session: IChatSession) => {
    const time = session.gmtModified || session.gmtCreate;

    if (!time) {
      return '';
    }

    const date = dayjs(time);

    if (!date.isValid()) {
      return '';
    }

    if (date.isSame(dayjs(), 'day')) {
      return date.format('HH:mm');
    }

    if (date.isSame(dayjs().subtract(1, 'day'), 'day')) {
      return i18n('stream.sidebar.yesterday');
    }

    if (date.isSame(dayjs(), 'year')) {
      return date.format('MM-DD');
    }

    return date.format('YYYY-MM-DD');
  }, []);

  const contextMenuActions = useMemo<ContextMenuAction<StreamSessionContextIntent>[]>(
    () => {
      return [
        {
          id: 'delete',
          label: i18n('common.button.delete'),
          danger: true,
          validateBeforeExecute: (intent) => sessions.some((session) => session.id === intent.targetSnapshot.sessionId),
          execute: (intent) => {
            modal.confirm({
              title: i18n('stream.sidebar.deleteConfirm'),
              okText: i18n('common.button.delete'),
              cancelText: i18n('common.button.cancel'),
              okButtonProps: { danger: true },
              onOk: () => onSessionDelete(intent.targetSnapshot.sessionId),
            });
          },
        },
      ];
    },
    [modal, onSessionDelete, sessions],
  );

  const handleContextMenu = useCallback((event: React.MouseEvent, session: IChatSession) => {
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({
      surface: 'streamSession',
      pointer: {
        x: event.clientX,
        y: event.clientY,
      },
      targetSnapshot: {
        sessionId: session.id,
        title: session.title,
      },
    });
  }, []);

  return (
    <MainSecondaryPanel className={styles.streamSidebar} width={260} bordered>
      {modalContextHolder}
      <PortalContextMenu
        intent={contextMenu}
        className={styles.contextMenu}
        actions={contextMenuActions}
        onClose={() => setContextMenu(null)}
      />
      <div className={styles.streamSidebarHeader}>
        <Button
          type="primary"
          className={styles.streamNewChatButton}
          icon={<MessageSquarePlus size={16} />}
          onClick={onNewChat}
        >
          {i18n('stream.panel.newChat')}
        </Button>
        <Tooltip title={i18n('stream.sidebar.search')} placement="bottom" mouseEnterDelay={0.3}>
          <span className={styles.streamSearchTooltipAnchor}>
            <IconButton
              size={{
                boxSize: 32,
                iconSize: 18,
              }}
              className={styles.streamSearchButton}
              icon={Search}
              onClick={() => onSearchOpenChange(!searchOpen)}
            />
          </span>
        </Tooltip>
      </div>
      {searchOpen && (
        <div className={styles.streamSearchWrap}>
          <Input
            ref={searchInputRef}
            size="small"
            value={searchKeyword}
            placeholder={i18n('stream.sidebar.searchPlaceholder')}
            prefix={<IconfontSvg code="icon-search" size={14} />}
            onChange={(e) => onSearchKeywordChange(e.target.value)}
            onBlur={onSearchBlur}
            allowClear
          />
        </div>
      )}
      <div className={styles.streamSectionTitle}>{i18n('stream.sidebar.recentChats')}</div>
      <div className={styles.streamSessionList}>
        {sessions.length === 0 && (
          <div className={styles.sessionEmpty}>
            {searchKeyword.trim() ? i18n('stream.sidebar.noSearchResult') : i18n('stream.sidebar.noHistory')}
          </div>
        )}
        {sessions.map((session) => (
          <div
            key={session.id}
            className={[
              styles.sidebarSessionItem,
              activeSessionId === session.id ? styles.sidebarSessionItemActive : '',
            ]
              .filter(Boolean)
              .join(' ')}
            onClick={() => onSessionClick(session)}
            onContextMenu={(event) => handleContextMenu(event, session)}
          >
            <div className={styles.sidebarSessionTitle}>{session.title || i18n('stream.sidebar.unnamed')}</div>
            <div className={styles.sidebarSessionTime}>{formatSessionTime(session)}</div>
          </div>
        ))}
      </div>
    </MainSecondaryPanel>
  );
};

export default StreamSidebar;
