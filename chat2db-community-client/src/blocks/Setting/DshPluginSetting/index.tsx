import dshWhaleLogo from '@/assets/logo/dsh/whale.svg';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';
import { Alert, Switch } from 'antd';
import { Cable } from 'lucide-react';
import { useStyles } from '../BaseSetting/style';
import { useDshPluginStyles } from './style';

export default function DshPluginSetting() {
  const { styles } = useStyles();
  const { styles: dshStyles } = useDshPluginStyles();
  const { enabled, setBaseSetting } = useGlobalStore((state) => ({
    enabled: state.baseSetting.enableDshPluginManagement === true,
    setBaseSetting: state.setBaseSetting,
  }));

  return (
    <div className={styles.baseSettingBox}>
      <div className={dshStyles.brandPanel}>
        <img className={dshStyles.brandLogo} src={dshWhaleLogo} alt="DeepSeek Harness" />
        <div className={dshStyles.brandCopy}>
          <div className={dshStyles.brandName}>DeepSeek Harness</div>
          <div className={dshStyles.brandDescription}>{i18n('setting.nav.dshPluginDescribe')}</div>
        </div>
      </div>
      <div className={styles.settingsList}>
        <section className={styles.settingRow} data-setting-search-id="dshPlugin.management">
          <div className={styles.settingMeta}>
            <Cable aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
            <div className={styles.settingMetaContent}>
              <div className={styles.settingTitle} data-setting-search-title="true">
                {i18n('setting.dshPlugin.enable')}
              </div>
              <div className={styles.settingDescription}>{i18n('setting.dshPlugin.enableDescribe')}</div>
            </div>
          </div>
          <div className={styles.settingControl}>
            <div className={styles.settingStack}>
              <Switch
                aria-label={i18n('setting.dshPlugin.enable')}
                className={dshStyles.switch}
                checked={enabled}
                onChange={(checked) => setBaseSetting({ enableDshPluginManagement: checked })}
              />
              <Alert showIcon type="info" message={i18n('setting.dshPlugin.scopeHint')} />
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
