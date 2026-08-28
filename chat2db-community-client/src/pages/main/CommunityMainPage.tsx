import { Confetti } from '@chat2db/ui';
import clientExtension from '@client-extension';
import { type InputRef } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import i18n from '@/i18n';
import { INavItem } from '@/typings/main';
import feedback from '@/utils/feedback';
import { useParams } from 'umi';

import { useUpdateEffect } from 'ahooks';

import { getConnectionEnvList } from '@/store/connection';
import { useGlobalStore } from '@/store/global';
import { useUserStore } from '@/store/session';

import CommunitySetting from '@/blocks/Setting/CommunitySetting';
import CommunityMainActionBar from './components/CommunityMainActionBar';
import CommunityTitleBarActions from './components/CommunityTitleBarActions';
import StreamSidebar from './components/StreamSidebar';

import Dashboard from './dashboard';
import { mergeNavigationItems } from '@/client-extension/merge';
import { createCoreMainNavItems } from './navigationItems';
import Workspace from './workspace';
import Stream from '../stream';

import { useStyles } from './style';

import { clientRuntime } from '@client-runtime';
import { IframeType } from '@/constants';
import aiStreamService, { IChatSession } from '@/service/aiStream';
import { useWorkspaceStore } from '@/store/workspace';
import { isDesktop, isHashHistoryEnv } from '@/utils/env';
import {
  APP_TITLE_BAR_ACTION_EVENT,
  AppTitleBarActionEventDetail,
  isAppTitleBarAction,
} from '@/utils/appTitleBarAction';
import {
  readPersistedMainPageActiveTab,
  resolveDesktopInitialMainPage,
  resolveInitialMainPage,
} from '@/utils/mainPageNavigation';
import { checkIsSharePage } from '@/utils/url';

function CommunityMainPage() {
  const [navConfig, setNavConfig] = useState<INavItem[]>([]);

  const allNavItems: INavItem[] = useMemo(
    () =>
      mergeNavigationItems(
        createCoreMainNavItems({
          stream: { component: <Stream />, name: i18n('stream.nav.title') },
          workspace: { component: <Workspace />, name: i18n('workspace.title') },
          dashboard: { component: <Dashboard />, name: i18n('dashboard.title') },
        }),
        clientExtension.navigationItems ?? [],
      ),
    [],
  );
  const initNavConfig = clientExtension.mainPage.useNavigationItems(allNavItems);

  const showLeftContainer = useMemo(() => checkIsSharePage(), []);

  const [sidebarSessions, setSidebarSessions] = useState<IChatSession[]>([]);
  const [sidebarSearchOpen, setSidebarSearchOpen] = useState(false);
  const [sidebarSearchKeyword, setSidebarSearchKeyword] = useState('');
  const sidebarSearchInputRef = useRef<InputRef>(null);
  const { styles } = useStyles({});
  const { tab: settingTab } = useParams<{ tab: string }>();

  const { curUser } = useUserStore((state) => ({
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
        tabObject.isLoad = true;
        setNavConfig([...navConfigTmp]);
        setMainPageActiveTab({ page, pathName, searchParams });
        setSettingPageActiveTab(false);

        if (shouldToggleWorkspacePanel) {
          useWorkspaceStore.getState().togglePanelLeft();
        }
      }
    },
    [setMainPageActiveTab, setSettingPageActiveTab],
  );

  const handleInitPage = useCallback(() => {
    let nextNavConfig = [...initNavConfig];

    if (!clientRuntime.showDashboard) {
      nextNavConfig = nextNavConfig.filter((item) => item.key !== 'dashboard');
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
          persistedPage = readPersistedMainPageActiveTab(localStorage.getItem(clientRuntime.globalStoreName));
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

    const resolvedPage =
      clientExtension.mainPage.resolveNavigationPage?.({
        requestedPage: page,
        allItems: allNavItems,
        visibleItems: nextNavConfig,
      }) ?? page;
    if (resolvedPage !== page) {
      page = resolvedPage;
      pathName = `/${resolvedPage}`;
    }

    handleChangePageTab({
      page,
      pathName,
      navConfigTmp: nextNavConfig,
      isFirst: true,
    });
  }, [allNavItems, handleChangePageTab, initNavConfig, mainPageActiveTab]);

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
      const { page } = (event as CustomEvent<{ page: string }>).detail;
      handleChangePageTab({ page, navConfigTmp: navConfig });
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

  const handleOpenSettings = useCallback(() => {
    setSettingPageActiveTab(settingPageActiveTab === false ? 'basic' : false);
  }, [setSettingPageActiveTab, settingPageActiveTab]);

  useEffect(() => {
    const handleTitleBarAction = (event: Event) => {
      const action = (event as CustomEvent<AppTitleBarActionEventDetail>).detail?.action;
      if (!isAppTitleBarAction(action)) {
        return;
      }

      // Mark valid Community title-bar actions as handled even when the entry is unavailable.
      event.preventDefault();
      if (showLeftContainer || isEmbedIframe === IframeType.ZOER) {
        return;
      }

      if (action === 'settings') {
        if (!isEmbedIframe) {
          handleOpenSettings();
        }
        return;
      }

      const navItem = navConfig.find((item) => item.key === action);
      if (navItem) {
        handleNavItemClick(navItem);
      }
    };

    window.addEventListener(APP_TITLE_BAR_ACTION_EVENT, handleTitleBarAction);
    return () => window.removeEventListener(APP_TITLE_BAR_ACTION_EVENT, handleTitleBarAction);
  }, [handleNavItemClick, handleOpenSettings, isEmbedIframe, navConfig, showLeftContainer]);

  useEffect(() => {
    const shouldShowWorkspaceTitleBarActions =
      !showLeftContainer &&
      isEmbedIframe !== IframeType.ZOER &&
      mainPageActiveTab === 'workspace' &&
      settingPageActiveTab === false;
    const shouldShowTitleBarActions =
      !showLeftContainer &&
      isEmbedIframe !== IframeType.ZOER &&
      (Boolean(clientExtension.mainPage.slots?.titleBarActions) || shouldShowWorkspaceTitleBarActions);

    if (!shouldShowTitleBarActions) {
      setAppTitleBarRightComponent(false);
      return;
    }

    setAppTitleBarRightComponent(
      <CommunityTitleBarActions
        extras={clientExtension.mainPage.slots?.titleBarActions}
        showWorkspaceActions={shouldShowWorkspaceTitleBarActions}
      />,
    );
  }, [
    isEmbedIframe,
    mainPageActiveTab,
    setAppTitleBarRightComponent,
    settingPageActiveTab,
    showLeftContainer,
  ]);

  useEffect(() => {
    return () => setAppTitleBarRightComponent(false);
  }, [setAppTitleBarRightComponent]);

  const showStreamSidebar =
    mainPageActiveTab === 'stream' &&
    settingPageActiveTab === false &&
    !showLeftContainer &&
    isEmbedIframe !== IframeType.ZOER;
  const showMainActionBar = !showLeftContainer && isEmbedIframe !== IframeType.ZOER;

  return (
    <div className={styles.container}>
      {showMainActionBar && (
        <CommunityMainActionBar
          navItems={navConfig}
          activePage={mainPageActiveTab}
          settingsActive={settingPageActiveTab !== false}
          hideSettings={
            Boolean(isEmbedIframe) || clientExtension.mainPage.hiddenCoreActions?.includes('settings') === true
          }
          beforeTerminal={
            clientExtension.mainPage.slots?.actionBarBeforeTerminal ??
            clientExtension.mainPage.slots?.actionBarFooter
          }
          afterTerminal={clientExtension.mainPage.slots?.actionBarAfterTerminal}
          onNavigate={handleNavItemClick}
          onOpenSettings={handleOpenSettings}
        />
      )}

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
