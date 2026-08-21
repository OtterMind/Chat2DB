import Logo from '@/components/Logo';
import { APP_CONFIG, APP_URL_CONFIG_COMMUNITY } from '@/constants/appConfig';
import { clientRuntime } from '@client-runtime';
import i18n from '@/i18n';
import { fetchLatestCommunityRelease, compareCommunityVersions, type CommunityRelease } from '@/service/communityRelease';
import { isCommunityEnv } from '@/utils/env';
import { openWebPage } from '@/utils/url';
import { staticMessage } from '@chat2db/ui';
import { Button } from 'antd';
import { useState } from 'react';
import { useStyles } from './style';

// About Us
export default function AboutUs() {
  const { styles } = useStyles();
  const [latestRelease, setLatestRelease] = useState<CommunityRelease>();
  const [checkingRelease, setCheckingRelease] = useState(false);

  const jumpDoc = () => {
    let CHANGE_LOG_URL = APP_URL_CONFIG_COMMUNITY.CHANGE_LOG_URL;
    if (clientRuntime.usesLocalPersistence) {
      CHANGE_LOG_URL = `${CHANGE_LOG_URL}?type=local`;
    }
    openWebPage(CHANGE_LOG_URL);
  };

  const checkUpdate = async () => {
    setLatestRelease(undefined);
    setCheckingRelease(true);
    try {
      const release = await fetchLatestCommunityRelease();
      setLatestRelease(release);
      if (compareCommunityVersions(release.version, __APP_VERSION__) > 0) {
        staticMessage.success(`${i18n('setting.text.newVersionAvailable')}: v${release.version}`);
      } else {
        staticMessage.info(i18n('setting.text.notAvailable'));
      }
    } catch {
      staticMessage.error(i18n('common.text.failure'));
    } finally {
      setCheckingRelease(false);
    }
  };

  const hasNewRelease = latestRelease && compareCommunityVersions(latestRelease.version, __APP_VERSION__) > 0;

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
            <span>{latestRelease?.version || __APP_VERSION__}</span>
          </div>
          {/* <div className={styles.buildTime}>
            <span>{i18n('setting.text.buildTime')}</span>
            <span>{__BUILD_TIME__}</span>
          </div> */}
          <div className={styles.updateButton}>
            {isCommunityEnv && (
              <Button
                type="primary"
                size="small"
                loading={checkingRelease}
                onClick={() => {
                  if (hasNewRelease && latestRelease) {
                    openWebPage(latestRelease.releaseUrl);
                  } else {
                    void checkUpdate();
                  }
                }}
              >
                {hasNewRelease ? i18n('setting.button.openRelease') : i18n('setting.title.checkUpdate')}
              </Button>
            )}
            {!clientRuntime.usesLocalPersistence && (
              <Button size="small" onClick={jumpDoc}>
                {i18n('setting.button.changeLog')}
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
