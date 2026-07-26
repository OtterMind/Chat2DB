import React, { forwardRef, memo, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { IconfontSvg, SearchBar } from '@chat2db/ui';

import i18n from '@/i18n';
import { LOCAL_SQL_SESSION_DRAG_TYPE, WorkspaceTabType, workspaceTabConfig } from '@/constants';
import {
  ShortcutAction,
  ShortcutOverrides,
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
} from '@/constants/shortcut';
import { useGlobalStore } from '@/store/global';
import { useWorkspaceStore } from '@/store/workspace';
import type { IWorkspaceTab } from '@/typings';
import LocalSQLFileTree, { type LocalSQLFileTreeRef } from '../LocalSQLFileTree';
import { useStyles } from './style';

const SESSION_PANEL_MIN_HEIGHT = 72;
const SESSION_PANEL_MAX_HEIGHT = 420;
type SearchBarHandle = { focus: () => void; blur: () => void };

interface WorkspaceExplorerProps {
  active?: boolean;
}

export interface WorkspaceExplorerRef {
  locateLocalFile: (filePath: string) => boolean;
}

const WorkspaceExplorer = memo(
  forwardRef<WorkspaceExplorerRef, WorkspaceExplorerProps>((
    { active = true },
    ref,
  ) => {
  const { styles } = useStyles();
  const explorerRef = useRef<HTMLDivElement>(null);
  const searchBarRef = useRef<SearchBarHandle>(null);
  const sessionSectionRef = useRef<HTMLElement>(null);
  const activeSessionRowRef = useRef<HTMLButtonElement | null>(null);
  const localFileTreeRef = useRef<LocalSQLFileTreeRef>(null);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [sessionPanelHeight, setSessionPanelHeight] = useState<number | null>(null);
  const { activeConsoleId, workspaceTabList, editorList, setActiveConsoleId } = useWorkspaceStore((state) => ({
    activeConsoleId: state.activeConsoleId,
    workspaceTabList: state.workspaceTabList,
    editorList: state.editorList,
    setActiveConsoleId: state.setActiveConsoleId,
  }));
  const shortcutOverrides = useGlobalStore((state) => state.shortcutOverrides);
  const shortcutConfig = useMemo(
    () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
    [shortcutOverrides],
  );

  const openSessions = useMemo(() => {
    return (workspaceTabList || []).filter((item) => {
      return item.type === WorkspaceTabType.CONSOLE;
    });
  }, [workspaceTabList]);

  const trimmedSearchKeyword = searchKeyword.trim();
  const filteredOpenSessions = useMemo(() => {
    const keyword = trimmedSearchKeyword.toLowerCase();
    if (!keyword) {
      return openSessions;
    }

    return openSessions.filter((session) => {
      const context = [
        session.uniqueData?.databaseName || session.uniqueData?.schemaName,
        session.uniqueData?.dataSourceName,
      ]
        .filter(Boolean)
        .join(' @ ');

      return [session.title, context]
        .filter(Boolean)
        .some((text) =>
          String(text)
            .toLowerCase()
            .includes(keyword),
        );
    });
  }, [openSessions, trimmedSearchKeyword]);

  useEffect(() => {
    if (!active) {
      return;
    }
    const activeRow = activeSessionRowRef.current;
    if (!activeRow) {
      const activeSessionIsFiltered =
        !!trimmedSearchKeyword && openSessions.some((session) => session.id === activeConsoleId);
      if (activeSessionIsFiltered) {
        setSearchKeyword('');
      }
      return;
    }

    window.requestAnimationFrame(() => {
      try {
        activeRow.scrollIntoView({ block: 'nearest' });
      } catch {
        activeRow.scrollIntoView(false);
      }
    });
  }, [active, activeConsoleId, filteredOpenSessions.length, openSessions, sessionPanelHeight, trimmedSearchKeyword]);

  useImperativeHandle(
    ref,
    () => ({
      locateLocalFile: (filePath: string) => !!localFileTreeRef.current?.locateFile(filePath),
    }),
    [],
  );

  useEffect(() => {
    if (!active) {
      return;
    }

    const searchArea = document.getElementById('tree-search-area');
    const handleKeyDown = (event: KeyboardEvent) => {
      if (isShortcutEventMatch(event, shortcutConfig[ShortcutAction.WorkspaceTreeSearch].binding)) {
        event.preventDefault();
        searchBarRef.current?.focus?.();
      }
    };

    searchArea?.addEventListener('keydown', handleKeyDown);
    return () => {
      searchArea?.removeEventListener('keydown', handleKeyDown);
    };
  }, [active, shortcutConfig]);

  function getSessionTitle(session: IWorkspaceTab) {
    return session.title || i18n('workspace.openSessions.untitled');
  }

  function getSessionContext(session: IWorkspaceTab) {
    return [session.uniqueData?.databaseName || session.uniqueData?.schemaName, session.uniqueData?.dataSourceName]
      .filter(Boolean)
      .join(' @ ');
  }

  function getSessionFileName(session: IWorkspaceTab) {
    const title =
      getSessionTitle(session)
        .split(/[\\/]/)
        .pop() || i18n('workspace.openSessions.untitled');
    return title.toLowerCase().endsWith('.sql') ? title.slice(0, -4) : title;
  }

  function getSessionContent(session: IWorkspaceTab) {
    const editor = editorList?.[session.id as number];
    if (editor?.getValue) {
      return editor.getValue();
    }
    return session.uniqueData?.ddl || '';
  }

  function handleSessionDragStart(event: React.DragEvent<HTMLButtonElement>, session: IWorkspaceTab) {
    setActiveConsoleId(session.id);
    const payload = {
      id: session.id,
      title: getSessionFileName(session),
      content: getSessionContent(session),
    };
    event.dataTransfer.effectAllowed = 'copy';
    event.dataTransfer.setData(LOCAL_SQL_SESSION_DRAG_TYPE, JSON.stringify(payload));
    event.dataTransfer.setData('text/plain', payload.title);
  }

  function clampSessionPanelHeight(height: number) {
    const explorerHeight = explorerRef.current?.clientHeight || SESSION_PANEL_MAX_HEIGHT;
    const maxHeight = Math.max(SESSION_PANEL_MIN_HEIGHT, Math.min(SESSION_PANEL_MAX_HEIGHT, explorerHeight - 120));
    return Math.max(SESSION_PANEL_MIN_HEIGHT, Math.min(maxHeight, height));
  }

  function handleSessionResizeStart(event: React.MouseEvent<HTMLDivElement>) {
    event.preventDefault();
    event.stopPropagation();

    const startY = event.clientY;
    const startHeight = sessionSectionRef.current?.getBoundingClientRect().height || SESSION_PANEL_MIN_HEIGHT;
    const originalCursor = document.body.style.cursor;
    const originalUserSelect = document.body.style.userSelect;

    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';

    const handleMouseMove = (moveEvent: MouseEvent) => {
      setSessionPanelHeight(clampSessionPanelHeight(startHeight + startY - moveEvent.clientY));
    };
    const handleMouseUp = () => {
      document.body.style.cursor = originalCursor;
      document.body.style.userSelect = originalUserSelect;
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
  }

  return (
    <div ref={explorerRef} className={styles.explorer}>
      <div className={styles.searchWrap}>
        <SearchBar
          ref={searchBarRef}
          className={styles.searchBar}
          value={searchKeyword}
          placeholder={i18n('common.text.search')}
          onChange={(event) => setSearchKeyword(event.target.value)}
        />
      </div>
      <section className={styles.fileSection}>
        <LocalSQLFileTree
          ref={localFileTreeRef}
          active={active}
        />
      </section>
      <section
        ref={sessionSectionRef}
        className={[styles.sessionSection, sessionPanelHeight ? styles.sessionSectionResized : '']
          .filter(Boolean)
          .join(' ')}
        style={sessionPanelHeight ? { height: sessionPanelHeight } : undefined}
      >
        <div className={styles.sessionResizeHandle} onMouseDown={handleSessionResizeStart} />
        <div className={styles.sectionHeader}>
          <span>{i18n('workspace.openSessions.title')}</span>
          <span className={styles.sectionCount}>
            {trimmedSearchKeyword ? `${filteredOpenSessions.length}/${openSessions.length}` : openSessions.length}
          </span>
        </div>
        <div className={[styles.sessionList, 'workspace-session-list'].join(' ')}>
          {!openSessions.length && <div className={styles.emptyText}>{i18n('workspace.openSessions.empty')}</div>}
          {openSessions.length > 0 && !filteredOpenSessions.length && (
            <div className={styles.emptyText}>{i18n('workspace.tips.noSearchResult')}</div>
          )}
          {filteredOpenSessions.map((session) => {
            const isActive = activeConsoleId === session.id;
            const context = getSessionContext(session);

            return (
              <button
                key={session.id}
                ref={(node) => {
                  if (isActive) {
                    activeSessionRowRef.current = node;
                  }
                }}
                type="button"
                draggable
                className={[styles.sessionRow, isActive ? styles.sessionRowActive : ''].filter(Boolean).join(' ')}
                onDragStart={(event) => handleSessionDragStart(event, session)}
                onClick={(event) => {
                  event.stopPropagation();
                  setActiveConsoleId(session.id);
                }}
              >
                <IconfontSvg
                  code={workspaceTabConfig[session.type]?.icon || 'icon-run-sql'}
                  size={14}
                  className={styles.sessionIcon}
                />
                <span className={styles.sessionMain}>
                  <span className={styles.sessionTitle}>{getSessionTitle(session)}</span>
                  {context && <span className={styles.sessionContext}>{context}</span>}
                </span>
              </button>
            );
          })}
        </div>
      </section>
    </div>
  );
  }),
);

export default WorkspaceExplorer;
