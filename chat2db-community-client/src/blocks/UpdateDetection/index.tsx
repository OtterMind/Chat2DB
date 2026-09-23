import { Platform } from '@/constants/os';
import { clientRuntime } from '@client-runtime';
import { UpdatedStatus } from '@/constants/settings';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { JavaPushActionType, JcefEventBus } from '@/jcef/eventBus';
import { useGlobalStore } from '@/store/global';
import { openWebPage } from '@/utils/url';
import { Icon } from '@chat2db/ui';
import { Button, notification } from 'antd';
import { useEffect, useRef } from 'react';
import { useStyles } from './style';
import { isCheckDue, nextCheckDueAt, nextNotifiedVersion, updateCheckDelayMs } from './updateCheckSchedule';

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

const UpdateDetection = ({ offlineActivation = false }: { offlineActivation?: boolean }) => {
  const { styles } = useStyles();
  const notifiedVersionRef = useRef('');

  const {
    appConfig,
    setUpdateDetail,
    appUrlConfig,
    hotUpdateConfig,
    updateDetail,
    handleCheckUpdate,
    updateAndRestartApp,
    syncUpdatePreferences,
    setOfflineActivation,
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
    setOfflineActivation: state.setOfflineActivation,
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
    setOfflineActivation(offlineActivation);
  }, [offlineActivation, setOfflineActivation]);

  useEffect(() => {
    if (!clientRuntime.enableAutoUpdate) {
      return;
    }
    // Check for updates, check for updates after app initialization is completed
    if (appConfig.isReady) {
      syncUpdatePreferences()
        .then(() => handleCheckUpdate('startup'))
        .catch(() => undefined);
    }
  }, [appConfig.isReady]);

  useEffect(() => {
    if (!clientRuntime.enableAutoUpdate || !appConfig.isReady) {
      return undefined;
    }
    // Keep checking while the desktop session runs: 30m, 1h, 2h, 4h, 6h and then repeat. The next round
    // is only scheduled after the previous check settles, so slow checks never stack up.
    let cancelled = false;
    let running = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let round = 0;
    let dueAt = 0;
    const runCheck = () => {
      if (running) {
        return Promise.resolve();
      }
      running = true;
      return handleCheckUpdate('scheduled')
        .catch(() => undefined)
        .finally(() => {
          running = false;
          if (!cancelled) {
            round += 1;
            scheduleNext();
          }
        });
    };
    const scheduleNext = () => {
      dueAt = nextCheckDueAt(Date.now(), round);
      timer = setTimeout(() => {
        if (!cancelled) {
          runCheck();
        }
      }, updateCheckDelayMs(round));
    };
    // A hidden renderer does not run its timers, and macOS can nap the whole process, so a check that
    // came due while the window was in the background is run as soon as the user comes back.
    const catchUp = () => {
      if (cancelled || document.visibilityState !== 'visible' || !isCheckDue(Date.now(), dueAt)) {
        return;
      }
      if (timer) {
        clearTimeout(timer);
      }
      runCheck();
    };
    scheduleNext();
    document.addEventListener('visibilitychange', catchUp);
    window.addEventListener('focus', catchUp);
    return () => {
      cancelled = true;
      if (timer) {
        clearTimeout(timer);
      }
      document.removeEventListener('visibilitychange', catchUp);
      window.removeEventListener('focus', catchUp);
    };
  }, [appConfig.isReady]);

  useEffect(() => {
    if (!clientRuntime.enableAutoUpdate) {
      return;
    }
    switch (updateDetail.status) {
      case UpdatedStatus.Available: {
        const nextNotified = nextNotifiedVersion(
          hotUpdateConfig.remindMe,
          updateDetail.version,
          notifiedVersionRef.current,
        );
        if (nextNotified !== notifiedVersionRef.current) {
          notifiedVersionRef.current = nextNotified;
          openFindNewVersionNotification();
        }
        if (hotUpdateConfig.autoDownload) {
          triggerDownload();
        }
        break;
      }
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
