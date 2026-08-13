import { Confetti, IconButton, IconfontSvg } from '@chat2db/ui';
import { Tooltip, type InputRef } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import i18n from '@/i18n';
import { INavItem } from '@/typings/main';
import feedback from '@/utils/feedback';
import { useParams } from 'umi';

import { useUpdateEffect } from 'ahooks';

import { getConnectionEnvList } from '@/store/connection';
import { useGlobalStore } from '@/store/global';
import { useUserStore } from '@/store/user';

import CommunitySetting from '@/blocks/Setting/CommunitySetting';
import OfflineAvatar from '@/blocks/PersonalCenter/components/OfflineAvatar';
import CustomLayout from '@/components/CustomLayout';
import StreamSidebar from './components/StreamSidebar';

import Dashboard from './dashboard';
import DashboardMenuList from './dashboard/DashboardMenuList';
import { createCoreMainNavItems } from './navigationItems';
import Workspace from './workspace';
import Tasks from './tasks';
import Stream from '../stream';

import { useStyles } from './style';

import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { IframeType } from '@/constants';
import aiStreamService, { IChatSession } from '@/service/aiStream';
import { useChatStore } from '@/store/chat';
import { useWorkspaceStore } from '@/store/workspace';
import { isDesktop, isHashHistoryEnv } from '@/utils/env';
import {
  readPersistedMainPageActiveTab,
  resolveDesktopInitialMainPage,
  resolveInitialMainPage,
} from '@/utils/mainPageNavigation';
import { checkIsSharePage } from '@/utils/url';

function CommunityMainPage() {
  const [navConfig, setNavConfig] = useState<INavItem[]>([]);

  const initNavConfig: INavItem[] = useMemo(
    () =>
      createCoreMainNavItems({
        stream: { component: <Stream />, name: i18n('stream.nav.title') },
        tasks: { component: <Tasks />, name: i18n('task.title') },
        workspace: { component: <Workspace />, name: i18n('workspace.title') },
        dashboard: { component: <Dashboard />, name: i18n('dashboard.title') },
      }),
    [],
  );

  const showLeftContainer = useMemo(() => checkIsSharePage(), []);

  const [sidebarExpanded, setSidebarExpanded] = useState(() => {
    return localStorage.getItem(runtimeEditionConfig.sidebarExpandedStorageKey) === 'true';
  });
  const toggleSidebar = useCallback(() => {
    setSidebarExpanded((prev) => {
      const next = !prev;
      localStorage.setItem(runtimeEditionConfig.sidebarExpandedStorageKey, String(next));
      return next;
    });
  }, []);
  const collapseSidebar = useCallback(() => {
    setSidebarExpanded(false);
    localStorage.setItem(runtimeEditionConfig.sidebarExpandedStorageKey, 'false');
  }, []);

  const [sidebarSessions, setSidebarSessions] = useState<IChatSession[]>([]);
  const [sidebarSearchOpen, setSidebarSearchOpen] = useState(false);
  const [sidebarSearchKeyword, setSidebarSearchKeyword] = useState('');
  const sidebarSearchInputRef = useRef<InputRef>(null);
  const { styles, cx } = useStyles({ sidebarExpanded });
  const { tab: settingTab } = useParams<{ tab: string }>();

  const { networkAbandoned, curUser } = useUserStore((state) => ({
    networkAbandoned: state.networkAbandoned,
    curUser: state.curUser,
  }));

  const {
    mainPageActiveTab,
    setMainPageActiveTab,
    setAppTitleBarRightComponent,
    settingPageActiveTab,
    setSettingPageActiveTab,
    triggerConfetti,
    isEmbedIframe,
  } = useGlobalStore((state) => ({
    mainPageActiveTab: state.mainPageActiveTab,
    setMainPageActiveTab: state.setMainPageActiveTab,
    setAppTitleBarRightComponent: state.setAppTitleBarRightComponent,
    settingPageActiveTab: state.settingPageActiveTab,
    setSettingPageActiveTab: state.setSettingPageActiveTab,
    triggerConfetti: state.triggerConfetti,
    isEmbedIframe: state.isEmbedIframe,
  }));

  const { currentChat, setCurrentChat } = useChatStore((state) => ({
    setCurrentChat: state.setCurrentChat,
    currentChat: state.currentChat,
  }));

  const [activeSessionId, setActiveSessionId] = useState<string | null>(() => {
    const parts = window.location.pathname.split('/');
    if (parts[1] === 'stream' && parts[2]) {
      return parts[2];
    }
    return null;
  });

  const loadSidebarSessions = useCallback(async () => {
    try {
      const result = (await aiStreamService.getChatSessions(undefined as void)) || [];
      setSidebarSessions(result);
    } catch (error) {
      console.warn('loadSidebarSessions failed', error);
    }
  }, []);

  const handleChangePageTab = useCallback(
    ({
      page,
      pathName,
      navConfigTmp,
      isFirst = false,
      searchParams,
    }: {
      page: string;
      navConfigTmp: INavItem[];
      pathName?: string;
      isFirst?: boolean;
      searchParams?: Record<string, string>;
    }) => {
      const tabObject = navConfigTmp.find((item) => `${item.key}` === page);

      if (tabObject?.onClick) {
        tabObject.onClick();
        return;
      }

      if (tabObject) {
        const { mainPageActiveTab: currentMainPageActiveTab, settingPageActiveTab: currentSettingPageActiveTab } =
          useGlobalStore.getState();
        const shouldToggleWorkspacePanel =
          page === 'workspace' &&
          page === currentMainPageActiveTab &&
          !isFirst &&
          currentSettingPageActiveTab === false;
        const shouldCollapseSidebarOnWorkspaceEnter =
          page === 'workspace' && (currentMainPageActiveTab !== 'workspace' || isFirst);

        tabObject.isLoad = true;
        setNavConfig([...navConfigTmp]);
        setMainPageActiveTab({ page, pathName, searchParams });
        setSettingPageActiveTab(false);

        if (shouldCollapseSidebarOnWorkspaceEnter) {
          collapseSidebar();
        }

        if (shouldToggleWorkspacePanel) {
          if (useWorkspaceStore.getState().layout.panelLeftWidth === 0) {
            useWorkspaceStore.getState().setPanelLeftWidth(240);
          } else {
            useWorkspaceStore.getState().setPanelLeftWidth(0);
          }
        }
      }
    },
    [collapseSidebar, setMainPageActiveTab, setSettingPageActiveTab],
  );

  const handleInitPage = useCallback(() => {
    let nextNavConfig = [...initNavConfig];

    if (!runtimeEditionConfig.dashboardEntry) {
      nextNavConfig = nextNavConfig.filter((item) => item.key !== 'dashboard');
    }

    if (networkAbandoned) {
      const filterKeys = ['stream', 'dashboard'];
      nextNavConfig = nextNavConfig.filter((item) => !filterKeys.includes(item.key));
    }

    setNavConfig(nextNavConfig);

    let page = '';
    let pathName = '';
    if (isHashHistoryEnv || isDesktop) {
      const hashPath = window.location.hash.replace(/^#/, '');
      const normalizedHashPath = hashPath.startsWith('/') ? hashPath : `/${hashPath}`;
      const hashPage = normalizedHashPath.split('/')[1];
      if (isDesktop) {
        let persistedPage: string | undefined;
        try {
          persistedPage = readPersistedMainPageActiveTab(localStorage.getItem(runtimeEditionConfig.globalStoreName));
        } catch {
          persistedPage = undefined;
        }
        const initialLocation = resolveDesktopInitialMainPage(
          normalizedHashPath,
          persistedPage,
          nextNavConfig.map((item) => `${item.key}`),
        );
        page = initialLocation.page;
        pathName = initialLocation.pathName;
      } else {
        page = resolveInitialMainPage(hashPage, mainPageActiveTab);
        pathName = hashPage ? normalizedHashPath : '';
      }
    } else {
      page = resolveInitialMainPage(window.location.pathname.split('/')[1], mainPageActiveTab);
      pathName = window.location.pathname;
    }

    if (page === 'connections') {
      page = 'workspace';
      pathName = '/workspace';
    }

    handleChangePageTab({
      page,
      pathName,
      navConfigTmp: nextNavConfig,
      isFirst: true,
    });
  }, [handleChangePageTab, initNavConfig, mainPageActiveTab, networkAbandoned]);

  useEffect(() => {
    if (mainPageActiveTab === 'stream') {
      loadSidebarSessions();
    }
  }, [mainPageActiveTab, loadSidebarSessions]);

  useEffect(() => {
    if (mainPageActiveTab === 'stream' && curUser?.id) {
      loadSidebarSessions();
    }
  }, [mainPageActiveTab, curUser?.id, loadSidebarSessions]);

  useEffect(() => {
    if (mainPageActiveTab === 'stream' && sidebarSearchOpen) {
      sidebarSearchInputRef.current?.focus({ cursor: 'end' });
    }
  }, [mainPageActiveTab, sidebarSearchOpen]);

  useEffect(() => {
    if (mainPageActiveTab !== 'stream') {
      setSidebarSearchOpen(false);
      setSidebarSearchKeyword('');
    }
  }, [mainPageActiveTab]);

  useEffect(() => {
    if (activeSessionId) {
      loadSidebarSessions();
    }
  }, [activeSessionId, loadSidebarSessions]);

  useEffect(() => {
    const handler = () => loadSidebarSessions();
    window.addEventListener('stream:sessionsChanged', handler);
    return () => window.removeEventListener('stream:sessionsChanged', handler);
  }, [loadSidebarSessions]);

  useEffect(() => {
    const handler = (event: Event) => {
      const { page, pathName } = (event as CustomEvent<{ page: string; pathName?: string }>).detail;
      handleChangePageTab({ page, pathName, navConfigTmp: navConfig });
    };
    window.addEventListener('app:navigateTo', handler);
    return () => window.removeEventListener('app:navigateTo', handler);
  }, [handleChangePageTab, navConfig]);

  const handleSidebarSessionClick = useCallback(
    (session: IChatSession) => {
      setActiveSessionId(session.id);
      handleChangePageTab({
        page: 'stream',
        navConfigTmp: navConfig,
        pathName: `/stream/${session.id}`,
      });
      window.dispatchEvent(
        new CustomEvent('stream:loadSession', { detail: { sessionId: session.id, title: session.title } }),
      );
    },
    [handleChangePageTab, navConfig],
  );

  const handleSidebarDeleteSession = useCallback(
    async (sessionId: string) => {
      try {
        await aiStreamService.deleteChatSession({ id: sessionId });
        setSidebarSessions((prev) => prev.filter((session) => session.id !== sessionId));
        if (activeSessionId === sessionId) {
          setActiveSessionId(null);
          window.dispatchEvent(new CustomEvent('stream:newChat'));
        }
      } catch {
        feedback.error(i18n('stream.sidebar.deleteFailed'));
      }
    },
    [activeSessionId],
  );

  const handleSidebarNewChat = useCallback(() => {
    setActiveSessionId(null);
    handleChangePageTab({ page: 'stream', navConfigTmp: navConfig, pathName: '/stream' });
    window.dispatchEvent(new CustomEvent('stream:newChat'));
  }, [handleChangePageTab, navConfig]);

  const handleSidebarSearchBlur = useCallback(() => {
    if (!sidebarSearchKeyword.trim()) {
      setSidebarSearchOpen(false);
    }
  }, [sidebarSearchKeyword]);

  const filteredSidebarSessions = useMemo(() => {
    const keyword = sidebarSearchKeyword.trim().toLowerCase();
    if (!keyword) {
      return sidebarSessions;
    }
    return sidebarSessions.filter((session) =>
      (session.title || i18n('stream.sidebar.unnamed')).toLowerCase().includes(keyword),
    );
  }, [sidebarSearchKeyword, sidebarSessions]);

  useEffect(() => {
    handleInitPage();
    getConnectionEnvList();
  }, [handleInitPage]);

  useEffect(() => {
    const pathName = window.location.pathname.split('/')[1];
    if (pathName === 'settings') {
      setSettingPageActiveTab(settingTab || 'basic');
    }
  }, [setSettingPageActiveTab, settingTab]);

  useEffect(() => {
    if (mainPageActiveTab === 'workspace') {
      setAppTitleBarRightComponent(<CustomLayout />);
    } else {
      setAppTitleBarRightComponent(false);
    }
    return () => {
      setAppTitleBarRightComponent(false);
    };
  }, [mainPageActiveTab, setAppTitleBarRightComponent]);

  useUpdateEffect(() => {
    if (!navConfig) {
      return;
    }
    const tabObject = navConfig.find((item) => `${item.key}` === mainPageActiveTab);
    if (tabObject) {
      tabObject.isLoad = true;
      setNavConfig([...navConfig]);
    }
    if (mainPageActiveTab === 'stream') {
      const parts = window.location.pathname.split('/');
      const chatId = parts[1] === 'stream' && parts[2] ? parts[2] : null;
      setActiveSessionId(chatId);
    }
  }, [mainPageActiveTab]);

  useEffect(() => {
    setCurrentChat({
      ...currentChat,
      [mainPageActiveTab]: currentChat[mainPageActiveTab],
    });
  }, [mainPageActiveTab]);

  const handleNavItemClick = useCallback(
    (item: INavItem) => {
      if (item.key === 'stream') {
        handleChangePageTab({
          page: 'stream',
          navConfigTmp: navConfig,
          pathName: activeSessionId ? `/stream/${activeSessionId}` : '/stream',
        });
        return;
      }

      handleChangePageTab({ page: item.key, navConfigTmp: navConfig });
    },
    [activeSessionId, handleChangePageTab, navConfig],
  );

  const showStreamSidebar =
    mainPageActiveTab === 'stream' &&
    settingPageActiveTab === false &&
    !showLeftContainer &&
    isEmbedIframe !== IframeType.ZOER;

  return (
    <div className={styles.container}>
      <div
        className={cx(styles.leftContainer, { [styles.leftContainerHidden]: showLeftContainer })}
        style={{ display: isEmbedIframe === IframeType.ZOER ? 'none' : 'flex' }}
      >
        <div className={styles.sidebarHeader}>
          <Tooltip
            title={sidebarExpanded ? i18n('stream.sidebar.collapse') : i18n('stream.sidebar.expand')}
            placement="right"
            mouseEnterDelay={0.3}
          >
            <span>
              <IconButton
                size={{
                  boxSize: 30,
                  iconSize: 22,
                }}
                className={styles.sidebarBtn}
                code="icon-chat-menu"
                onClick={toggleSidebar}
              />
            </span>
          </Tooltip>
          {sidebarExpanded && <span className={styles.sidebarHeaderSpacer} />}
        </div>

        <div className={styles.navContainer}>
          {navConfig.map((item) => {
            const isActive = item.key === mainPageActiveTab && settingPageActiveTab === false;
            const NavIcon = item.icon;

            if (!sidebarExpanded) {
              return (
                <IconButton
                  type="primary"
                  isActive={isActive}
                  key={item.key}
                  size={{
                    boxSize: 34,
                    iconSize: 18,
                  }}
                  title={item.name}
                  icon={NavIcon}
                  tooltipPlacement="right"
                  onClick={() => handleNavItemClick(item)}
                />
              );
            }

            return (
              <div
                key={item.key}
                className={cx(styles.navItem, isActive && styles.navItemActive)}
                onClick={() => handleNavItemClick(item)}
              >
                <NavIcon className={styles.navItemIcon} size={18} />
                <span className={styles.navItemLabel}>{item.name}</span>
              </div>
            );
          })}
        </div>

        {runtimeEditionConfig.dashboardEntry && mainPageActiveTab === 'dashboard' && (
          <div className={styles.sessionSection}>
            <DashboardMenuList />
          </div>
        )}

        {!isEmbedIframe && (
          <div className={styles.bottomNav}>
            {sidebarExpanded ? (
              <div className={styles.navItem} onClick={() => setSettingPageActiveTab('basic')}>
                <IconfontSvg code="icon-adjustments" className={styles.navItemIcon} size={18} />
                <span className={styles.navItemLabel}>{i18n('setting.title.setting')}</span>
              </div>
            ) : (
              <OfflineAvatar logoSize={24} triggerSize={34} />
            )}
          </div>
        )}
      </div>

      {showStreamSidebar && (
        <StreamSidebar
          sessions={filteredSidebarSessions}
          activeSessionId={activeSessionId}
          searchOpen={sidebarSearchOpen}
          searchKeyword={sidebarSearchKeyword}
          searchInputRef={sidebarSearchInputRef}
          onSearchOpenChange={setSidebarSearchOpen}
          onSearchKeywordChange={setSidebarSearchKeyword}
          onSearchBlur={handleSidebarSearchBlur}
          onNewChat={handleSidebarNewChat}
          onSessionClick={handleSidebarSessionClick}
          onSessionDelete={handleSidebarDeleteSession}
        />
      )}

      <div className={styles.rightContainer}>
        {navConfig.map((item) => (
          <div
            key={item.key}
            className={styles.componentBox}
            hidden={mainPageActiveTab !== item.key || settingPageActiveTab !== false}
          >
            {item.isLoad ? item.component : null}
          </div>
        ))}
        {settingPageActiveTab !== false && <CommunitySetting />}
      </div>

      <Confetti active={triggerConfetti} />
    </div>
  );
}

export default CommunityMainPage;
