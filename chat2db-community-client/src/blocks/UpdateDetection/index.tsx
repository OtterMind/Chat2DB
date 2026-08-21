import { Platform } from '@/constants/os';
import { clientRuntime } from '@client-runtime';
import { UpdatedStatus } from '@/constants/settings';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { JavaPushActionType, JcefEventBus } from '@/jcef/eventBus';
import { useGlobalStore } from '@/store/global';
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

  const {
    appConfig,
    setUpdateDetail,
    appUrlConfig,
    hotUpdateConfig,
    updateDetail,
    handleCheckUpdate,
    updateAndRestartApp,
    syncUpdatePreferences,
    setSettingPageActiveTab,
  } = useGlobalStore((state) => ({
    appConfig: state.appConfig,
    appUrlConfig: state.appUrlConfig,
    hotUpdateConfig: state.hotUpdateConfig,
    setUpdateDetail: state.setUpdateDetail,
    updateDetail: state.updateDetail,
    handleCheckUpdate: state.handleCheckUpdate,
    updateAndRestartApp: state.updateAndRestartApp,
    syncUpdatePreferences: state.syncUpdatePreferences,
    setSettingPageActiveTab: state.setSettingPageActiveTab,
  }));

  const [notificationApi, notificationDom] = notification.useNotification({
    maxCount: 1,
    top: createTop(),
  });

  const triggerDownload = () => {
    jcefApi
      .triggerDownload()
      .then((accepted) => {
        if (!accepted) {
          setUpdateDetail({ status: UpdatedStatus.UpdateFailed });
        }
      })
      .catch(() => {
        setUpdateDetail({ status: UpdatedStatus.UpdateFailed });
      });
  };

  useEffect(() => {
    if (!clientRuntime.enableAutoUpdate) {
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
    if (!clientRuntime.enableAutoUpdate) {
      return;
    }
    // Check for updates, check for updates after app initialization is completed
    if (appConfig.isReady) {
      syncUpdatePreferences()
        .then(() => handleCheckUpdate())
        .catch(() => undefined);
      jcefApi
        .getUpdateRecoveryStatus()
        .then((status) => {
          if (status.failed) {
            openRecoveryNotification(status.fromVersion, status.toVersion);
          }
        })
        .catch(() => undefined);
    }
  }, [appConfig.isReady]);

  useEffect(() => {
    if (!clientRuntime.enableAutoUpdate) {
      return;
    }
    switch (updateDetail.status) {
      case UpdatedStatus.Available:
        if (hotUpdateConfig.remindMe) {
          openFindNewVersionNotification();
        }
        if (hotUpdateConfig.autoDownload) {
          triggerDownload();
        }
        break;
      case UpdatedStatus.Updated:
        if (hotUpdateConfig.autoInstall) {
          updateAndRestartApp();
          return;
        }
        openNotificationAuto();
        break;
      case UpdatedStatus.Installed:
        openNotificationAuto();
        break;
      case UpdatedStatus.UpdateFailed:
        staticMessage.error(i18n('common.text.failure'));
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

  const openRecoveryNotification = (fromVersion: string, toVersion: string) => {
    const key = 'update-recovery-failed';
    const btn = (
      <Button
        type="link"
        size="small"
        onClick={() => {
          jcefApi.openUpdateRecoveryLog().catch(() => undefined);
        }}
      >
        {i18n('setting.button.openUpdateLog')}
      </Button>
    );
    notificationApi.error({
      className: styles.notification,
      duration: null,
      message: i18n('setting.text.updateRecoveryFailedTitle'),
      description: (
        <div>
          <div>{i18n('setting.text.updateRecoveryFailed', toVersion, fromVersion)}</div>
          <div>{btn}</div>
        </div>
      ),
      key,
    });
  };

  // Notify the user when a new version is available.
  const openFindNewVersionNotification = () => {
    const key = `open${Date.now()}`;
    let CHANGE_LOG_URL = appUrlConfig.CHANGE_LOG_URL;
    if (clientRuntime.usesLocalPersistence) {
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
