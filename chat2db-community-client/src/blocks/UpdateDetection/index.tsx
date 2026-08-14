import { Platform } from '@/constants/os';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { UpdatedStatus } from '@/constants/settings';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { JavaPushActionType, JcefEventBus } from '@/jcef/eventBus';
import { getManualRecoveryAction } from '@/store/global/slices/hotUpdate/action';
import { useGlobalStore } from '@/store/global';
import { isDesktop, isDevelopment } from '@/utils/env';
import { openWebPage } from '@/utils/url';
import { IUpdateDetail } from '@/typings/settings';
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

const getFailureDetail = (reason?: string): string | undefined => {
  switch (reason) {
    case 'NETWORK':
      return i18n('setting.text.updateFailureNetwork');
    case 'INVALID_MANIFEST':
      return i18n('setting.text.updateFailureInvalidManifest');
    case 'CHECKSUM_MISMATCH':
      return i18n('setting.text.updateFailureChecksumMismatch');
    case 'UNSUPPORTED_REDIRECT':
      return i18n('setting.text.updateFailureUnsupportedRedirect');
    default:
      return undefined;
  }
};

const UpdateDetection = () => {
  const { styles } = useStyles();
  const isDevelopmentDesktop = isDesktop && isDevelopment;

  const {
    appConfig,
    setUpdateDetail,
    downloadUpdate,
    appUrlConfig,
    hotUpdateConfig,
    updateDetail,
    handleCheckUpdate,
    updateAndRestartApp,
    syncUpdatePreferences,
    setSettingPageActiveTab,
  } = useGlobalStore((state) => ({
    appConfig: state.appConfig,
    setUpdateDetail: state.setUpdateDetail,
    appUrlConfig: state.appUrlConfig,
    hotUpdateConfig: state.hotUpdateConfig,
    downloadUpdate: state.downloadUpdate,
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

  useEffect(() => {
    if (!runtimeEditionConfig.autoUpdate) {
      return;
    }
    JcefEventBus.on(
      JavaPushActionType.AUTO_PROGRESS,
      (data: IUpdateDetail) => {
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
    if (appConfig.isReady) {
      void syncUpdatePreferences();
      if (hotUpdateConfig.remindMe) {
        void handleCheckUpdate();
      }
      void jcefApi
        .getUpdateRecoveryStatus()
        .then((status) => {
          if (status.failed) {
            openRecoveryNotification(status.fromVersion, status.toVersion);
          }
        })
        .catch(() => undefined);
    }
  }, [appConfig.isReady, hotUpdateConfig.remindMe, syncUpdatePreferences]);

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
      case UpdatedStatus.UpdateFailed:
        openUpdateFailedNotification();
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
    notificationApi.error({
      className: styles.notification,
      duration: null,
      message: i18n('setting.text.updateRecoveryFailedTitle'),
      description: (
        <div>
          <div>{i18n('setting.text.updateRecoveryFailed', toVersion, fromVersion)}</div>
          <Button type="link" size="small" onClick={() => void jcefApi.openUpdateRecoveryLog()}>
            {i18n('setting.button.openUpdateLog')}
          </Button>
        </div>
      ),
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

  // Notify the user when an automatic check or download failed and offer a manual GitHub Release fallback.
  const openUpdateFailedNotification = () => {
    const key = 'update-failed';
    const recoveryAction = getManualRecoveryAction(updateDetail);
    const isCheckFailure = !updateDetail.version || updateDetail.failureStage === 'CHECK';
    const message = isCheckFailure
      ? i18n('setting.text.updateCheckFailed')
      : i18n('setting.text.updateDownloadFailed');
    const failureDetail = getFailureDetail(updateDetail.failureReason);

    const btn = recoveryAction ? (
      <div className={styles.btnBox}>
        <Button
          type="link"
          size="small"
          onClick={() => {
            openWebPage(recoveryAction.url);
            staticMessage.success(i18n('setting.text.changeLogOpenedInBrowser'));
            notificationApi.destroy();
          }}
        >
          {recoveryAction.version
            ? i18n('setting.button.downloadVersionManually', `v${recoveryAction.version}`)
            : i18n('setting.button.openGitHubReleases')}
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
    ) : null;

    notificationApi.error({
      className: styles.notification,
      duration: null,
      message,
      description: (
        <div>
          {failureDetail && <div>{failureDetail}</div>}
          {btn}
        </div>
      ),
      key,
    });
  };

  return <>{notificationDom}</>;
};

export default UpdateDetection;
