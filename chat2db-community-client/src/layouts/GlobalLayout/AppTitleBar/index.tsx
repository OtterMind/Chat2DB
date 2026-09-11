import { Copy, Minus, Square, X } from 'lucide-react';
import { memo, type MouseEvent, useCallback, useEffect, useState } from 'react';
import { useStyles } from './style';
import { Dropdown, type MenuProps } from 'antd';
import { refreshPage } from '@/utils';
import { history } from 'umi';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import { isCommunityEnv, isDesktop } from '@/utils/env';
import DesktopAppMenu from './DesktopAppMenu';
import { COMMUNITY_TITLE_BAR_HEIGHT } from '@/constants/mainLayout';
import { resolveTitleBarPlatform, shouldUseWindowsDesktopChrome } from './platform';

interface AppBarProps {
  className?: string;
}

const AppBar = memo<AppBarProps>(({ className }) => {
  const { styles, cx } = useStyles();
  const appTitleBarRightComponent = useGlobalStore((state) => state.appTitleBarRightComponent);
  const { isMac, isWindows } = resolveTitleBarPlatform(window.navigator.os_type, window.navigator.userAgent);
  const useWindowsDesktopChrome = shouldUseWindowsDesktopChrome(isWindows, isDesktop);
  const useIntegratedTitleBar = isCommunityEnv || useWindowsDesktopChrome;
  const [isMaximized, setIsMaximized] = useState(false);

  const syncWindowMaximized = useCallback(() => {
    jcefApi
      .isWindowMaximized()
      .then(setIsMaximized)
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    if (!useWindowsDesktopChrome) {
      return;
    }

    let resizeTimer: ReturnType<typeof setTimeout> | undefined;
    const handleResize = () => {
      window.clearTimeout(resizeTimer);
      resizeTimer = window.setTimeout(syncWindowMaximized, 80);
    };

    syncWindowMaximized();
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('resize', handleResize);
      window.clearTimeout(resizeTimer);
    };
  }, [syncWindowMaximized, useWindowsDesktopChrome]);

  const items: MenuProps['items'] = [
    {
      key: '1',
      label: 'Open log',
      onClick: () => {
        jcefApi?.openLog();
      },
    },
    {
      key: '2',
      label: 'Open the console',
      onClick: () => {
        jcefApi?.openDevTools();
      },
    },
    {
      key: '3',
      label: 'Refresh app',
      onClick: refreshPage,
    },
    {
      key: '4',
      label: 'test-jcef',
      onClick: () => {
        history.push('/test-jcef');
      },
    },
    {
      key: '5',
      label: 'go-back-home',
      onClick: () => {
        history.push('/');
      },
    },
  ];

  const toggleWindowMaximized = useCallback(() => {
    return jcefApi.handleDoubleClickAppBar().then((maximized) => {
      if (isWindows) {
        setIsMaximized(maximized);
      }
    });
  }, [isWindows]);

  const handleDoubleClick = () => {
    if (!isDesktop) {
      return;
    }
    toggleWindowMaximized().catch(() => undefined);
  };

  const handleMinimizeWindow = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    jcefApi.minimizeWindow();
  };

  const handleToggleMaximizeWindow = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (event.detail > 1) {
      return;
    }
    toggleWindowMaximized().catch(() => undefined);
  };

  const handleCloseWindow = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    jcefApi.closeWindow();
  };

  if (!isMac && !useIntegratedTitleBar) {
    // const showLeftContainer = checkIsSharePage();
    // if (__WEBAPP__ && !isEmbedIframe && !showLeftContainer) {
    //   window._appTitleBarHeight = COMMUNITY_TITLE_BAR_HEIGHT;
    //   return (
    //     <div
    //       style={{
    //         height: COMMUNITY_TITLE_BAR_HEIGHT,
    //         display: 'flex',
    //         alignItems: 'center',
    //         justifyContent: 'center',
    //       }}
    //     >
    //       {i18n('common.text.pleaseDownloadClient')}
    //       <Button type="link" href={appUrlConfig.DOWNLOAD_URL} target="_blank">
    //         {i18n('common.button.download')}
    //       </Button>
    //     </div>
    //   );
    // }
    window._appTitleBarHeight = 0;
    return <></>;
  }

  window._appTitleBarHeight = useIntegratedTitleBar ? COMMUNITY_TITLE_BAR_HEIGHT : 30;

  // When testing appBar on the web side, comment out the if else code above and open the comment code below.
  // window._appTitleBarHeight = COMMUNITY_TITLE_BAR_HEIGHT;

  return (
    <div
      className={cx(
        styles.appBar,
        {
          [styles.windowsAppBar]: !isMac,
          [styles.integratedAppBar]: useIntegratedTitleBar,
        },
        className,
      )}
      onDoubleClick={handleDoubleClick}
    >
      {useWindowsDesktopChrome && (
        <div className={styles.desktopMenu}>
          <DesktopAppMenu />
        </div>
      )}
      {appTitleBarRightComponent && (
        <div
          className={cx(styles.titleBarActions, {
            [styles.windowsDesktopTitleBarActions]: useWindowsDesktopChrome,
          })}
        >
          {appTitleBarRightComponent}
        </div>
      )}
      <div className={cx(styles.logoContainer, { [styles.integratedLogoContainer]: useIntegratedTitleBar })}>
        {!isMac && !useIntegratedTitleBar ? (
          <Dropdown destroyPopupOnHide menu={{ items }} trigger={['click']} className={styles.dropdown}>
            <div className={styles.appName}>Chat2DB</div>
          </Dropdown>
        ) : (
          <div className={cx(styles.appName, { [styles.integratedAppName]: useIntegratedTitleBar })}>Chat2DB</div>
        )}
      </div>
      {useWindowsDesktopChrome && (
        <div className={styles.windowsActionBar} onDoubleClick={(event) => event.stopPropagation()}>
          <button
            type="button"
            className={styles.windowsAction}
            aria-label="Minimize window"
            title="Minimize"
            onClick={handleMinimizeWindow}
          >
            <Minus size={16} strokeWidth={1.75} />
          </button>
          <button
            type="button"
            className={styles.windowsAction}
            aria-label={isMaximized ? 'Restore window' : 'Maximize window'}
            title={isMaximized ? 'Restore' : 'Maximize'}
            onClick={handleToggleMaximizeWindow}
          >
            {isMaximized ? <Copy size={14} strokeWidth={1.75} /> : <Square size={13} strokeWidth={1.75} />}
          </button>
          <button
            type="button"
            className={cx(styles.windowsAction, styles.closeAction)}
            aria-label="Close window"
            title="Close"
            onClick={handleCloseWindow}
          >
            <X size={16} strokeWidth={1.75} />
          </button>
        </div>
      )}
    </div>
  );
});

export default AppBar;
