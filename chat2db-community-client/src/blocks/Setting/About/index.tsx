import Iconfont from '@/components/Iconfont';
import Logo from '@/components/Logo';
import { APP_CONFIG } from '@/constants/appConfig';
import { clientRuntime } from '@client-runtime';
import { UpdatedStatus } from '@/constants/settings';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import { isDesktop } from '@/utils/env';
import { openWebPage } from '@/utils/url';
import { staticMessage } from '@chat2db/ui';
import { Button, Checkbox, Progress } from 'antd';
import { useMemo } from 'react';
import { useStyles } from './style';

// About Us
export default function AboutUs() {
  const { styles } = useStyles();
  const {
    appUrlConfig,
    hotUpdateConfig,
    updateDetail,
    updateHotUpdateConfig,
    updateAndRestartApp,
    handleCheckUpdate,
    setUpdateDetail,
  } = useGlobalStore((state) => ({
    appUrlConfig: state.appUrlConfig,
    hotUpdateConfig: state.hotUpdateConfig,
    updateDetail: state.updateDetail,
    updateHotUpdateConfig: state.updateHotUpdateConfig,
    updateAndRestartApp: state.updateAndRestartApp,
    handleCheckUpdate: state.handleCheckUpdate,
    setUpdateDetail: state.setUpdateDetail,
  }));

  const jumpDoc = () => {
    let CHANGE_LOG_URL = appUrlConfig.CHANGE_LOG_URL;
    if (clientRuntime.usesLocalPersistence) {
      CHANGE_LOG_URL = `${CHANGE_LOG_URL}?type=local`;
    }
    openWebPage(CHANGE_LOG_URL);
  };

  const checkUpdate = () => {
    handleCheckUpdate().then((available) => {
      if (available) {
        return;
      }
      if (useGlobalStore.getState().updateDetail.status === UpdatedStatus.UpdateFailed) {
        staticMessage.error(i18n('common.text.failure'));
        return;
      }
      staticMessage.info(i18n('setting.text.notAvailable'));
    });
  };

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

  const updateButton = useMemo(() => {
    if (!isDesktop || !clientRuntime.enableAutoUpdate) {
      return false;
    }
    switch (updateDetail.status) {
      case UpdatedStatus.Available:
        return (
          <Button type="primary" size="small" onClick={triggerDownload}>
            {i18n('setting.button.startDownloading')}
          </Button>
        );
      case UpdatedStatus.Updating:
        return (
          <Button type="primary" size="small" loading>
            {i18n('setting.button.beDownloading')}
          </Button>
        );
      case UpdatedStatus.Installing:
        return (
          <Button size="small" loading icon={<Iconfont code="&#xe662;" />} type="primary">
            {i18n('setting.button.installing')}
          </Button>
        );
      case UpdatedStatus.Updated:
      case UpdatedStatus.Installed:
        return (
          <Button size="small" icon={<Iconfont code="&#xe662;" />} type="primary" onClick={updateAndRestartApp}>
            {i18n('setting.button.restart')}
          </Button>
        );
      default:
        return (
          <Button onClick={checkUpdate} type="primary" size="small">
            {i18n('setting.title.checkUpdate')}
          </Button>
        );
    }
  }, [updateDetail, hotUpdateConfig]);

  return (
    <div>
      <div className={styles.versionsInfo}>
        <Logo size={98} className={styles.brandLogo} />
        <div>
          <div className={styles.currentVersion}>
            <span className={styles.appName}>{APP_CONFIG.displayName}</span>
            <span>{__APP_VERSION__}</span>
          </div>
          <div className={styles.newVersion} onClick={jumpDoc}>
            <span>{i18n('setting.text.latestVersion')}</span>
            <span>{updateDetail.version || __APP_VERSION__}</span>
          </div>
          {/* <div className={styles.buildTime}>
            <span>{i18n('setting.text.buildTime')}</span>
            <span>{__BUILD_TIME__}</span>
          </div> */}
          <div className={styles.updateButton}>
            {updateButton}
            {!clientRuntime.usesLocalPersistence && (
              <Button size="small" onClick={jumpDoc}>
                {i18n('setting.button.changeLog')}
              </Button>
            )}
          </div>
        </div>
      </div>
      {isDesktop && clientRuntime.enableAutoUpdate && (
        <>
          {!!updateDetail.progress && (
            <div className={styles.updateRule}>
              <div className={styles.updateRuleTitle}>{i18n('setting.text.downloadProgress')}</div>
              <div className={styles.downloadProgress}>
                <Progress percent={updateDetail.progress} />
              </div>
            </div>
          )}
          <div className={styles.updateRule}>
            <div className={styles.updateRuleTitle}>{i18n('setting.title.updateRule')}</div>
            <div className={styles.checkboxBox}>
              <Checkbox
                onChange={(e) => {
                  updateHotUpdateConfig('remindMe', e.target.checked);
                }}
                checked={hotUpdateConfig.remindMe}
              >
                {i18n('setting.text.alertNewVersion')}
              </Checkbox>
              <Checkbox
                onChange={(e) => {
                  updateHotUpdateConfig('autoDownload', e.target.checked);
                }}
                checked={hotUpdateConfig.autoDownload}
              >
                {i18n('setting.text.downloadNewVersion')}
              </Checkbox>
              <Checkbox
                onChange={(e) => {
                  updateHotUpdateConfig('autoInstall', e.target.checked);
                }}
                checked={hotUpdateConfig.autoInstall}
              >
                {i18n('setting.text.autoInstallNewVersion')}
              </Checkbox>
              <Checkbox
                onChange={(e) => {
                  updateHotUpdateConfig('receiveBeta', e.target.checked);
                }}
                checked={Boolean(hotUpdateConfig.receiveBeta)}
              >
                {i18n('setting.text.receiveBetaVersion')}
              </Checkbox>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
