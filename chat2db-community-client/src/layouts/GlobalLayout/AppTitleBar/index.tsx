import { Copy, Minus, Square, X } from 'lucide-react';
import { memo, type MouseEvent, useCallback, useEffect, useState } from 'react';
import { useStyles } from './style';
import { Dropdown, type MenuProps } from 'antd';
import { refreshPage } from '@/utils';
import { history } from 'umi';
import { Platform } from '@/constants/os';
import jcefApi from '@/jcef';
import { JcefEventBus, JavaPushActionType } from '@/jcef/eventBus';
import { useGlobalStore } from '@/store/global';
import { isCommunityEnv, isDesktop } from '@/utils/env';

interface AppBarProps {
  className?: string;
}

const AppBar = memo<AppBarProps>(({ className }) => {
  const { styles, cx } = useStyles();
  const appTitleBarRightComponent = useGlobalStore((state) => state.appTitleBarRightComponent);
  const isMac = window.navigator.os_type === Platform.Mac;
  const isWindows = window.navigator.os_type === Platform.Windows;
  const [isWindowFullScreen, setIsWindowFullScreen] = useState(false);
  const [isMaximized, setIsMaximized] = useState(false);

  const syncWindowMaximized = useCallback(() => {
    jcefApi
      .isWindowMaximized()
      .then(setIsMaximized)
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    if (!isCommunityEnv || !isWindows || !isDesktop) {
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
  }, [isWindows, syncWindowMaximized]);

  useEffect(() => {
    if (!isCommunityEnv || !isMac || !isDesktop) {
      return;
    }

    const handleWindowFullScreenChange = (message: { data?: boolean } | boolean) => {
      setIsWindowFullScreen(typeof message === 'boolean' ? message : message?.data === true);
    };

    JcefEventBus.on(JavaPushActionType.WINDOW_FULL_SCREEN_CHANGED, handleWindowFullScreenChange);
    jcefApi
      .isWindowFullScreen()
      .then(setIsWindowFullScreen)
      .catch(() => undefined);

    return () => {
      JcefEventBus.off(JavaPushActionType.WINDOW_FULL_SCREEN_CHANGED, handleWindowFullScreenChange);
    };
  }, [isMac]);

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

  const handleDoubleClick = async () => {
    jcefApi?.handleDoubleClickAppBar();
  };

  const handleMinimizeWindow = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    jcefApi.minimizeWindow();
  };

  const handleToggleMaximizeWindow = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    jcefApi
      .handleDoubleClickAppBar()
      .then(syncWindowMaximized)
      .catch(() => undefined);
  };

  const handleCloseWindow = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    jcefApi.closeWindow();
  };

  if (!isMac && !isCommunityEnv) {
    // const showLeftContainer = checkIsSharePage();
    // if (__WEBAPP__ && !isEmbedIframe && !showLeftContainer) {
    //   window._appTitleBarHeight = 36;
    //   return (
    //     <div style={{ height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
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

  window._appTitleBarHeight = isCommunityEnv ? 36 : 30;

  // When testing appBar on the web side, comment out the if else code above and open the comment code below.
  // window._appTitleBarHeight = 36;

  return (
    <div
      className={cx(
        styles.appBar,
        {
          [styles.windowsAppBar]: !isMac,
          [styles.communityAppBar]: isCommunityEnv,
        },
        className,
      )}
      onDoubleClick={handleDoubleClick}
    >
      {isCommunityEnv && (
        <div
          className={cx(styles.communityActions, {
          [styles.communityMacWindowedActions]: isMac && !isWindowFullScreen,
          [styles.communityWindowsDesktopActions]: isWindows && isDesktop,
        })}
        >
          {appTitleBarRightComponent}
        </div>
      )}
      <div className={cx(styles.logoContainer, { [styles.communityLogoContainer]: isCommunityEnv })}>
        {!isMac && !isCommunityEnv ? (
          <Dropdown destroyPopupOnHide menu={{ items }} trigger={['click']} className={styles.dropdown}>
            <div className={styles.appName}>Chat2DB</div>
          </Dropdown>
        ) : (
          <div className={cx(styles.appName, { [styles.communityAppName]: isCommunityEnv })}>Chat2DB</div>
        )}
      </div>
      {isCommunityEnv && isWindows && isDesktop && (
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
