import Iconfont from '@/components/Iconfont';
import Logo from '@/components/Logo';
import { APP_CONFIG } from '@/constants/appConfig';
import { clientRuntime } from '@client-runtime';
import { UpdatedStatus } from '@/constants/settings';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';
import {
  getManualDownloadAction,
  isWindowsDesktopUpdatePlatform,
} from '@/store/global/slices/hotUpdate/action';
import { isCommunityEnv, isDesktop, isDevelopment } from '@/utils/env';
import { openWebPage } from '@/utils/url';
import { staticMessage } from '@chat2db/ui';
import { Button, Checkbox, Progress } from 'antd';
import { useMemo, useRef } from 'react';
import { useStyles } from './style';

// About Us
export default function AboutUs() {
  const { styles } = useStyles();
  const isDevelopmentDesktop = isDesktop && isDevelopment;
  const isWindowsUpdatePlatform = isWindowsDesktopUpdatePlatform();
  const lastCheckClickAtRef = useRef(Number.NEGATIVE_INFINITY);
  const {
    appUrlConfig,
    hotUpdateConfig,
    updateDetail,
    updateHotUpdateConfig,
    updateAndRestartApp,
    handleCheckUpdate,
    downloadUpdate,
  } =
    useGlobalStore((state) => ({
      appUrlConfig: state.appUrlConfig,
      hotUpdateConfig: state.hotUpdateConfig,
      updateDetail: state.updateDetail,
      updateHotUpdateConfig: state.updateHotUpdateConfig,
      updateAndRestartApp: state.updateAndRestartApp,
      handleCheckUpdate: state.handleCheckUpdate,
      downloadUpdate: state.downloadUpdate,
    }));

  const jumpDoc = () => {
    let CHANGE_LOG_URL = appUrlConfig.CHANGE_LOG_URL;
    if (clientRuntime.usesLocalPersistence) {
      CHANGE_LOG_URL = `${CHANGE_LOG_URL}?type=local`;
    }
    openWebPage(CHANGE_LOG_URL);
  };

  const checkUpdate = () => {
    if (useGlobalStore.getState().updateDetail.status === UpdatedStatus.Checking) {
      return;
    }
    if (Date.now() - lastCheckClickAtRef.current < 5_000) {
      return;
    }
    lastCheckClickAtRef.current = Date.now();
    handleCheckUpdate().then((available) => {
      if (available) {
        return;
      }
      if (useGlobalStore.getState().updateDetail.status === UpdatedStatus.UpdateFailed) {
        staticMessage.error(i18n('setting.text.updateCheckFailed'));
        return;
      }
      staticMessage.info(i18n('setting.text.notAvailable'));
    });
  };

  const startDownload = async () => {
    const downloaded = await downloadUpdate();
    if (!downloaded) {
      staticMessage.error(i18n('setting.text.updateDownloadFailed'));
    }
  };

  const viewChangeLog = () => {
    jumpDoc();
    staticMessage.success(i18n('setting.text.changeLogOpenedInBrowser'));
  };

  const updateButton = useMemo(() => {
    if (!isDesktop || !clientRuntime.enableAutoUpdate) {
      return false;
    }
    switch (updateDetail.status) {
      case UpdatedStatus.Checking:
        return (
          <Button type="primary" size="small" loading>
            {i18n('setting.button.checkingUpdate')}
          </Button>
        );
      case UpdatedStatus.Available: {
        if (isDevelopmentDesktop) {
          return (
            <Button type="primary" size="small" disabled>
              {i18n('setting.button.developmentCheckOnly')}
            </Button>
          );
        }
        const manualDownload = getManualDownloadAction(updateDetail);
        return isWindowsUpdatePlatform ? (
          <Button
            type="primary"
            size="small"
            onClick={startDownload}
          >
            {i18n('setting.button.startDownloading')}
          </Button>
        ) : (
          <Button
            type="primary"
            size="small"
            disabled={!manualDownload}
            onClick={() => manualDownload && openWebPage(manualDownload.url)}
          >
            {i18n('setting.button.goToDownload')}
          </Button>
        );
      }
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
        if (isDevelopmentDesktop) {
          return (
            <Button size="small" disabled>
              {i18n('setting.button.developmentCheckOnly')}
            </Button>
          );
        }
        return (
          <Button size="small" icon={<Iconfont code="&#xe662;" />} type="primary" onClick={updateAndRestartApp}>
            {i18n('setting.button.restart')}
          </Button>
        );
      case UpdatedStatus.UpdateFailed:
        if (updateDetail.failureStage === 'INSTALL') {
          return (
            <Button size="small" icon={<Iconfont code="&#xe662;" />} type="primary" onClick={updateAndRestartApp}>
              {i18n('setting.button.retryInstallation')}
            </Button>
          );
        }
        return (
          <Button onClick={checkUpdate} type="primary" size="small">
            {i18n('setting.title.checkUpdate')}
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
            {(isCommunityEnv || !clientRuntime.usesLocalPersistence) && (
              <Button size="small" onClick={viewChangeLog}>
                {i18n('setting.button.changeLog')}
              </Button>
            )}
          </div>
          {isDevelopmentDesktop && updateDetail.status === UpdatedStatus.Available && (
            <div className={styles.developmentUpdateHint}>
              {i18n('setting.text.developmentUpdateOnlyCheck')}
            </div>
          )}
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
                disabled={!hotUpdateConfig.remindMe || isDevelopmentDesktop || !isWindowsUpdatePlatform}
                onChange={(e) => {
                  updateHotUpdateConfig('autoDownload', e.target.checked);
                }}
                checked={hotUpdateConfig.autoDownload}
              >
                {i18n('setting.text.downloadNewVersion')}
              </Checkbox>
              <Checkbox
                disabled={!hotUpdateConfig.remindMe || isDevelopmentDesktop || !isWindowsUpdatePlatform}
                onChange={(e) => {
                  updateHotUpdateConfig('autoInstall', e.target.checked);
                }}
                checked={hotUpdateConfig.autoInstall}
              >
                {i18n('setting.text.autoInstallNewVersion')}
              </Checkbox>
              {isCommunityEnv ? (
                <div className={styles.developmentUpdateHint}>
                  {i18n('setting.text.receiveBetaUnavailable')}
                </div>
              ) : (
                <Checkbox
                  onChange={(e) => {
                    void updateHotUpdateConfig('receiveBeta', e.target.checked);
                  }}
                  checked={Boolean(hotUpdateConfig.receiveBeta)}
                >
                  {i18n('setting.text.receiveBetaVersion')}
                </Checkbox>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
