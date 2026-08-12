import { Platform } from '@/constants/os';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { UpdatedStatus } from '@/constants/settings';
import i18n from '@/i18n';
import { JavaPushActionType, JcefEventBus } from '@/jcef/eventBus';
import { useGlobalStore } from '@/store/global';
import { isDesktop, isDevelopment } from '@/utils/env';
import { openWebPage } from '@/utils/url';
import { Icon, staticMessage } from '@chat2db/ui';
import { Button, notification } from 'antd';
import { useEffect } from 'react';
import { useStyles } from './style';

const createTop = () => {
  switch (window.navigator.os_type) {
    case Platform.Mac:
      return 48;
    case Platform.Windows:
      return 50;
    default:
      return 26;
  }
};

const UpdateDetection = () => {
  const { styles } = useStyles();
  const isDevelopmentDesktop = isDesktop && isDevelopment;

  const {
    appConfig,
    downloadUpdate,
    appUrlConfig,
    hotUpdateConfig,
    updateDetail,
    handleCheckUpdate,
    updateAndRestartApp,
    setSettingPageActiveTab,
  } = useGlobalStore((state) => ({
    appConfig: state.appConfig,
    appUrlConfig: state.appUrlConfig,
    hotUpdateConfig: state.hotUpdateConfig,
    downloadUpdate: state.downloadUpdate,
    updateDetail: state.updateDetail,
    handleCheckUpdate: state.handleCheckUpdate,
    updateAndRestartApp: state.updateAndRestartApp,
    setSettingPageActiveTab: state.setSettingPageActiveTab,
  }));

  const [notificationApi, notificationDom] = notification.useNotification({
    maxCount: 1,
    top: createTop(),
  });

  useEffect(() => {
    if (!runtimeEditionConfig.autoUpdate) {
      return;
    }
    JcefEventBus.on(
      JavaPushActionType.AUTO_PROGRESS,
      (data: {
        status: UpdatedStatus; // update status
        progress: number; // update progress
      }) => {
        setUpdateDetail(data);
      },
    );
    return () => {
      JcefEventBus.off(JavaPushActionType.AUTO_PROGRESS);
    };
  }, []);

  useEffect(() => {
    if (!runtimeEditionConfig.autoUpdate) {
      return;
    }
    // Community remains offline-first until the user explicitly enables update reminders.
    if (appConfig.isReady && hotUpdateConfig.remindMe) {
      handleCheckUpdate();
    }
  }, [appConfig.isReady, hotUpdateConfig.remindMe]);

  useEffect(() => {
    if (!runtimeEditionConfig.autoUpdate) {
      return;
    }
    switch (updateDetail.status) {
      case UpdatedStatus.Available:
        if (hotUpdateConfig.remindMe) {
          openFindNewVersionNotification();
        }
        if (!isDevelopmentDesktop && hotUpdateConfig.autoDownload) {
          void downloadUpdate();
        }
        break;
      case UpdatedStatus.Updated:
        if (!isDevelopmentDesktop && hotUpdateConfig.autoInstall) {
          void updateAndRestartApp();
          return;
        }
        if (isDevelopmentDesktop) {
          return;
        }
        openNotificationAuto();
        break;
      case UpdatedStatus.Installed:
        if (isDevelopmentDesktop) {
          return;
        }
        openNotificationAuto();
        break;
      default:
        break;
    }
  }, [updateDetail.status]);

  const openNotificationAuto = () => {
    const key = `open${Date.now()}`;
    const btn = (
      <div className={styles.btnBox}>
        <Button type="link" size="small" onClick={updateAndRestartApp}>
          {i18n('setting.button.restart')}
        </Button>
        <Button
          type="link"
          size="small"
          onClick={() => {
            notificationApi.destroy();
          }}
        >
          {i18n('common.text.laterOn')}
        </Button>
      </div>
    );
    notificationApi.open({
      className: styles.notification,
      duration: null,
      message: (
        <div className={styles.updateReminder}>
          <div className={styles.bell}>
            <Icon icon="&#xe661;" />
          </div>
          {i18n('setting.text.newEditionIsReady')}
        </div>
      ),
      description: btn,
      key,
    });
  };

  // Notify the user when a new version is available.
  const openFindNewVersionNotification = () => {
    const key = `open${Date.now()}`;
    let CHANGE_LOG_URL = appUrlConfig.CHANGE_LOG_URL;
    if (runtimeEditionConfig.localPersistence) {
      CHANGE_LOG_URL = `${CHANGE_LOG_URL}?type=local`;
    }

    const btn = (
      <div className={styles.btnBox}>
        <Button
          type="link"
          size="small"
          onClick={() => {
            setSettingPageActiveTab('about');
            notificationApi.destroy();
          }}
        >
          {i18n('setting.button.goToUpdate')}
        </Button>
        <Button
          type="link"
          size="small"
          onClick={() => {
            openWebPage(CHANGE_LOG_URL);
            staticMessage.success(i18n('setting.text.changeLogOpenedInBrowser'));
            notificationApi.destroy();
          }}
        >
          {i18n('setting.text.updateLog')}
        </Button>
      </div>
    );

    notificationApi.open({
      className: styles.notification,
      duration: null,
      message: (
        <div className={styles.updateReminder}>
          <div className={styles.bell}>
            <Icon icon="&#xe661;" />
          </div>
          {i18n('setting.text.discoverNewVersion', `v${updateDetail.version || '0.0.0'}`)}
        </div>
      ),
      style: {
        width: 300,
      },
      description: btn,
      key,
    });
  };

  return <>{notificationDom}</>;
};

export default UpdateDetection;
