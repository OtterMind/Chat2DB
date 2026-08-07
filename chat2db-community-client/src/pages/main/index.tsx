import { Confetti, IconButton, IconfontSvg } from '@chat2db/ui';
import { Tooltip, type InputRef } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import i18n from '@/i18n';
import { OrganizationType } from '@/typings/enterprise/organization';
import { INavItem } from '@/typings/main';
import feedback from '@/utils/feedback';
import { useParams } from 'umi';

// ----- hooks -----
import { useUpdateEffect } from 'ahooks';

// ----- store -----
import { getConnectionEnvList } from '@/store/connection';
import { useGlobalStore } from '@/store/global';
import { useOrgStore } from '@/store/organization';
import { useUserStore } from '@/store/user';

// ----- component -----
import PersonalCenter, { OfflineAvatar } from '@/blocks/PersonalCenter';
import Setting from '@/blocks/Setting';
import CustomLayout from '@/components/CustomLayout';
import UpgradeButton from '@/components/UpgradeButton';
import StreamSidebar from './components/StreamSidebar';

// ----- block -----
import Dashboard from './dashboard';
import DashboardMenuList from './dashboard/DashboardMenuList';
import { createCoreMainNavItems } from './navigationItems';
import Workspace from './workspace';
// import KnowledgeManagement from './knowledgeManagement';
import Stream from '../stream';
// import Plugin from './plugin';

import { useStyles } from './style';

import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { isDesktop, isHashHistoryEnv, isOfflineEnv, isWebEnv } from '@/utils/env';
import { resolveInitialMainPage } from '@/utils/mainPageNavigation';
import { checkIsSharePage } from '@/utils/url';
// import { refreshPage } from '@/utils';
import { SubscriptionType } from '@/constants/subscriptionType';

import GuideDialog from '@/components/GuideDialog';
import LicenseDialog from '@/components/LicenseDialog';
import { IframeType } from '@/constants';
import aiStreamService, { IChatSession } from '@/service/aiStream';
import { useChatStore } from '@/store/chat';
import { useWorkspaceStore } from '@/store/workspace';
import Organization from './organization';
import CreateOrJoinOrgDialog from './organization/components/CreateOrJoinOrgDialog';

function MainPage() {
  const [navConfig, setNavConfig] = useState<INavItem[]>([]);

  const initNavConfig: INavItem[] = useMemo(() => {
    return [
      ...createCoreMainNavItems({
        stream: { component: <Stream />, name: i18n('stream.nav.title') },
        workspace: { component: <Workspace />, name: i18n('workspace.title') },
        dashboard: { component: <Dashboard />, name: i18n('dashboard.title') },
      }),
      // {
      //   key: 'knowledge-management',
      //   icon: 'icon-knowledge-management',
      //   isLoad: false,
      //   component: <KnowledgeManagement />,
      //   name: i18n('knowledgeManagement.title'),
      // },
      // {
      //   key: 'plugin',
      //   icon: 'icon-xingzhuangjiehe',
      //   isLoad: false,
      //   component: <Plugin />,
      //   name: i18n('plugin.title'),
      // },
      {
        key: 'team',
        icon: 'icon-a-xunwen1',
        isLoad: false,
        component: <Organization />,
        name: i18n('team.title'),
      },
    ];
  }, []);

  const showLeftContainer = useMemo(() => {
    return checkIsSharePage();
  }, []);

  // ── Sidebar expand/collapse ──────────────────────────────────────────────
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

  // ── Sidebar session history ──────────────────────────────────────────────
  const [sidebarSessions, setSidebarSessions] = useState<IChatSession[]>([]);
  const [sidebarSearchOpen, setSidebarSearchOpen] = useState(false);
  const [sidebarSearchKeyword, setSidebarSearchKeyword] = useState('');
  const sidebarSearchInputRef = useRef<InputRef>(null);
  const { styles, cx } = useStyles({ sidebarExpanded });
  const { tab: settingTab } = useParams<{ tab: string }>();
  const { curOrg } = useOrgStore((state) => ({
    curOrg: state.curOrg,
  }));
  const { networkAbandoned, setPricingModalStatus, setSubscriptType, curUser } = useUserStore((state) => ({
    networkAbandoned: state.networkAbandoned,
    setPricingModalStatus: state.setPricingModalStatus,
    setSubscriptType: state.setSubscriptType,
    curUser: state.curUser,
  }));

  const {
    mainPageActiveTab,
    setMainPageActiveTab,
    setAppTitleBarRightComponent,
    settingPageActiveTab,
    setSettingPageActiveTab,
    triggerConfetti,
    setOpenGuideDialog,
    guideDialogStatus,
    openGuideDialog,
    openLinenseDialog,
    isEmbedIframe,
    isCN,
  } = useGlobalStore((s) => {
    return {
      mainPageActiveTab: s.mainPageActiveTab,
      setMainPageActiveTab: s.setMainPageActiveTab,
      setAppTitleBarRightComponent: s.setAppTitleBarRightComponent,
      settingPageActiveTab: s.settingPageActiveTab,
      setSettingPageActiveTab: s.setSettingPageActiveTab,
      triggerConfetti: s.triggerConfetti,
      openGuideDialog: s.openGuideDialog,
      guideDialogStatus: s.guideDialogStatus,
      setOpenGuideDialog: s.setOpenGuideDialog,
      openLinenseDialog: s.openLinenseDialog,
      isEmbedIframe: s.isEmbedIframe,
      isCN: s.appConfig.isCN,
    };
  });

  const { currentChat, setCurrentChat } = useChatStore((s) => ({
    setCurrentChat: s.setCurrentChat,
    currentChat: s.currentChat,
  }));

  const { openCreateOrJoinOrgDialog } = useOrgStore((s) => ({
    openCreateOrJoinOrgDialog: s.openCreateOrJoinOrgDialog,
  }));

  // const { getDataCollectionList } = useAIStore((s) => ({
  //   getDataCollectionList: s.getDataCollectionList,
  // }));

  // Read chatId from the initial URL so direct /stream/:chatId visits focus it automatically.
  const [activeSessionId, setActiveSessionId] = useState<string | null>(() => {
    const parts = window.location.pathname.split('/');
    if (parts[1] === 'stream' && parts[2]) return parts[2];
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
      const tabObject = navConfigTmp.find((t) => `${t.key}` === page);

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
    let _initNavConfig = [...initNavConfig];

    // Show the team entry only for confirmed team or enterprise organizations.
    // Hide it while curOrg is unloaded and for PERSONAL or offline contexts.
    const isTeamOrg = curOrg?.type === OrganizationType.TEAM || curOrg?.type === OrganizationType.ENTERPRISE;
    if (!isTeamOrg || !runtimeEditionConfig.teamWorkspace) {
      _initNavConfig = _initNavConfig.filter((item) => item.key !== 'team');
    }

    if (!runtimeEditionConfig.dashboardEntry) {
      _initNavConfig = _initNavConfig.filter((item) => item.key !== 'dashboard');
    }

    if (!isDesktop) {
      _initNavConfig = _initNavConfig.filter((item) => item.key !== 'plugin');
    }

    if (!isCN) {
      _initNavConfig = _initNavConfig.filter((item) => item.key !== 'knowledge-management');
    }

    if (networkAbandoned) {
      // plugin || knowledge-management || stream || chat || team || dashboard
      const filterKeys = ['plugin', 'knowledge-management', 'stream', 'chat', 'team', 'dashboard'];
      _initNavConfig = _initNavConfig.filter((item) => !filterKeys.includes(item.key));
    }

    setNavConfig(_initNavConfig);

    let page = '';
    let pathName = '';
    // Prefer the explicit route, then the persisted selection, then workspace.
    if (isHashHistoryEnv || isDesktop) {
      const hashPath = window.location.hash.replace(/^#/, '');
      const normalizedHashPath = hashPath.startsWith('/') ? hashPath : `/${hashPath}`;
      const hashPage = normalizedHashPath.split('/')[1];
      page = resolveInitialMainPage(hashPage, mainPageActiveTab);
      pathName = hashPage ? normalizedHashPath : '';
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
      pathName: pathName,
      navConfigTmp: _initNavConfig,
      isFirst: true,
    });
  }, [curOrg?.type, handleChangePageTab, initNavConfig, isCN, mainPageActiveTab, networkAbandoned]);

  // Load sessions when the chat entry is active.
  useEffect(() => {
    if (mainPageActiveTab === 'stream') {
      loadSidebarSessions();
    }
  }, [mainPageActiveTab, loadSidebarSessions]);

  // Reload after entering stream or when user data is ready to avoid a stale empty state from an early initial request.
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

  // Preload sessions for direct /stream/:chatId visits even when the sidebar is collapsed.
  useEffect(() => {
    if (activeSessionId) {
      loadSidebarSessions();
    }
  }, [activeSessionId, loadSidebarSessions]);

  // Listen for session updates from stream page (e.g. new session created)
  useEffect(() => {
    const handler = () => loadSidebarSessions();
    window.addEventListener('stream:sessionsChanged', handler);
    return () => window.removeEventListener('stream:sessionsChanged', handler);
  }, [loadSidebarSessions]);

  // Listen for in-app navigation requests (e.g. from WorkspaceRightEmpty AI intro)
  useEffect(() => {
    const handler = (e: Event) => {
      const { page } = (e as CustomEvent<{ page: string }>).detail;
      handleChangePageTab({ page, navConfigTmp: navConfig });
    };
    window.addEventListener('app:navigateTo', handler);
    return () => window.removeEventListener('app:navigateTo', handler);
  }, [handleChangePageTab, navConfig]);

  const handleSidebarSessionClick = useCallback(
    (session: IChatSession) => {
      setActiveSessionId(session.id);
      // Switch to stream using the /stream/:chatId path format.
      handleChangePageTab({
        page: 'stream',
        navConfigTmp: navConfig,
        pathName: `/stream/${session.id}`,
      });
      // Notify an already mounted Stream component through the fast event path.
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
        setSidebarSessions((prev) => prev.filter((s) => s.id !== sessionId));
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
    // getDataCollectionList();
  }, [handleInitPage]);

  useEffect(() => {
    // Check whether the current page is Settings.
    const pathName = window.location.pathname.split('/')[1];
    if (pathName === 'settings') {
      setSettingPageActiveTab(settingTab || 'basic');
    }
  }, []);

  // Show the custom layout on the workspace page.
  useEffect(() => {
    if (mainPageActiveTab === 'workspace') {
      setAppTitleBarRightComponent(<CustomLayout />);
    } else {
      setAppTitleBarRightComponent(false);
    }
    return () => {
      setAppTitleBarRightComponent(false);
    };
  }, [mainPageActiveTab]);

  useUpdateEffect(() => {
    if (!navConfig) return;
    const tabObject = navConfig.find((t) => `${t.key}` === mainPageActiveTab);
    if (tabObject) {
      // Mark the view as loaded.
      tabObject.isLoad = true;
      setNavConfig([...navConfig]);
    }
    if (mainPageActiveTab === 'stream') {
      // When returning to stream, focus the chatId from the URL; stream:loadSession loads its data.
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
        {/* ── Sidebar header: toggle + search ── */}
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
            {/* <div className={styles.sidebarBtn}>
              <Menu size={20} />
            </div> */}
          </Tooltip>
          {sidebarExpanded && <span className={styles.sidebarHeaderSpacer} />}
        </div>

        {/* ── Nav items ── */}
        <div className={styles.navContainer}>
          {navConfig.map((item) => {
            if (isEmbedIframe && item.key === 'dashboard') {
              return null;
            }
            const isActive = item.key === mainPageActiveTab && settingPageActiveTab === false;
            const NavIcon = typeof item.icon === 'string' ? null : item.icon;

            if (!sidebarExpanded) {
              return (
                <IconButton
                  type="primary"
                  isActive={isActive}
                  key={item.key}
                  size={{
                    boxSize: 34,
                    iconSize: NavIcon ? 18 : 22,
                  }}
                  title={item.name}
                  code={NavIcon ? undefined : item.icon}
                  icon={NavIcon || undefined}
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
                {NavIcon ? (
                  <NavIcon className={styles.navItemIcon} size={18} />
                ) : (
                  <IconfontSvg code={item.icon} className={styles.navItemIcon} size={20} />
                )}
                <span className={styles.navItemLabel}>{item.name}</span>
              </div>
            );
          })}
        </div>

        {/* ── Dashboard list (expanded only) ── */}
        {runtimeEditionConfig.dashboardEntry && mainPageActiveTab === 'dashboard' && (
          <div className={styles.sessionSection}>
            <DashboardMenuList />
          </div>
        )}

        {/* ── Bottom nav ── */}
        {!isEmbedIframe && (
          <div className={styles.bottomNav}>
            {sidebarExpanded ? (
              <>
                {/* <div className={styles.navItem} onClick={refreshPage}>
                  <IconfontSvg code="icon-refresh" className={styles.navItemIcon} size={20} />
                  <span className={styles.navItemLabel}>{i18n('common.button.refresh')}</span>
                </div> */}
                {runtimeEditionConfig.upgradeEntry && !isOfflineEnv && !curOrg?.vip && (
                  <div
                    className={styles.navItem}
                    onClick={() => {
                      setPricingModalStatus(true);
                      setSubscriptType(
                        curOrg?.type === OrganizationType.PERSONAL
                          ? SubscriptionType.PersonalUpdate
                          : SubscriptionType.TeamUpdate,
                      );
                    }}
                  >
                    <IconfontSvg code="icon-update" className={styles.navItemIcon} size={20} />
                    <span className={styles.navItemLabel}>{i18n('common.text.upgrade')}</span>
                  </div>
                )}
                {runtimeEditionConfig.downloadEntry && isWebEnv && !isDesktop && (
                  <div
                    className={styles.navItem}
                    onClick={() => {
                      const host = window.location.hostname;
                      const url = host.includes('chat2db-ai.com')
                        ? 'https://chat2db-ai.com/download'
                        : 'https://chat2db.ai/download';
                      window.open(url, '_blank');
                    }}
                  >
                    <IconfontSvg code="icon-download" className={styles.navItemIcon} size={20} />
                    <span className={styles.navItemLabel}>{i18n('common.button.download')}</span>
                  </div>
                )}
                {!runtimeEditionConfig.accountCenter ? (
                  <div className={styles.navItem} onClick={() => setSettingPageActiveTab('basic')}>
                    <IconfontSvg code="icon-adjustments" className={styles.navItemIcon} size={20} />
                    <span className={styles.navItemLabel}>{i18n('setting.title.setting')}</span>
                  </div>
                ) : (
                  <div className={styles.navItem}>
                    <PersonalCenter triggerSize={24}>
                      <span className={styles.navItemLabel}>
                        {curUser?.displayName || i18n('setting.label.personal')}
                      </span>
                    </PersonalCenter>
                  </div>
                )}
              </>
            ) : (
              <>
                {/* <RefreshButton /> */}
                {runtimeEditionConfig.upgradeEntry ? <UpgradeButton /> : null}
                {runtimeEditionConfig.downloadEntry && isWebEnv && !isDesktop && (
                  <IconButton
                    title={i18n('common.button.download')}
                    tooltipPlacement="right"
                    code="icon-download"
                    size={{ boxSize: 34, iconSize: 22 }}
                    onClick={() => {
                      const host = window.location.hostname;
                      const url = host.includes('chat2db-ai.com')
                        ? 'https://chat2db-ai.com/download'
                        : 'https://chat2db.ai/download';
                      window.open(url, '_blank');
                    }}
                  />
                )}
                {runtimeEditionConfig.accountCenter ? <PersonalCenter triggerSize={26} /> : <OfflineAvatar />}
              </>
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
        {settingPageActiveTab !== false && <Setting />}
      </div>

      <GuideDialog
        open={!isEmbedIframe && openGuideDialog}
        status={guideDialogStatus}
        onCancel={() => {
          setOpenGuideDialog(false);
        }}
      />
      <CreateOrJoinOrgDialog open={openCreateOrJoinOrgDialog} />
      <LicenseDialog open={openLinenseDialog} />
      <Confetti active={triggerConfetti} />
    </div>
  );
}

export default MainPage;
