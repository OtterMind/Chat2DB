import { DEFAULT_TERMINAL_SETTINGS, TERMINAL_THEMES } from '@/constants/terminal';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import type { TerminalOpenPosition, TerminalShellId, TerminalThemeId } from '@/typings/settings';
import { Alert, Segmented, Select, Spin, Switch } from 'antd';
import { Check, ChevronDown, Palette, PanelBottom, PanelRight, PanelTop, ShieldCheck, Terminal } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useStyles } from './style';

interface ShellOption {
  id: TerminalShellId;
  label: string;
  available: boolean;
}

const themeOptions = Object.values(TERMINAL_THEMES);

export default function TerminalSetting() {
  const { styles, cx } = useStyles();
  const { terminalSettings, updateTerminalSettings } = useGlobalStore((state) => ({
    terminalSettings: {
      ...DEFAULT_TERMINAL_SETTINGS,
      ...state.terminalSettings,
    },
    updateTerminalSettings: state.updateTerminalSettings,
  }));
  const [shells, setShells] = useState<ShellOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    let disposed = false;
    jcefApi
      .getTerminalCapabilities()
      .then((capabilities) => {
        if (disposed) {
          return;
        }
        const availableShells = capabilities.shells.filter((shell) => shell.available) as ShellOption[];
        setShells(availableShells);
        if (!availableShells.some((shell) => shell.id === terminalSettings.shellId)) {
          updateTerminalSettings({ shellId: 'system' });
        }
      })
      .catch((error) => {
        console.error('get terminal capabilities error', error);
        if (!disposed) {
          setLoadFailed(true);
        }
      })
      .finally(() => {
        if (!disposed) {
          setLoading(false);
        }
      });
    return () => {
      disposed = true;
    };
  }, [terminalSettings.shellId, updateTerminalSettings]);

  return (
    <div className={styles.settingsList}>
      <section className={styles.settingRow} data-setting-group="position" data-setting-search-id="terminal.position">
        <div className={styles.settingMeta}>
          <PanelTop aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.terminal.openPosition')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.terminal.openPositionDescribe')}</div>
          </div>
        </div>
        <div className={styles.settingControl}>
          <Segmented
            block
            aria-label={i18n('setting.terminal.openPosition')}
            className={styles.positionSegmented}
            value={terminalSettings.openPosition}
            options={[
              {
                value: 'tab',
                label: (
                  <span className={styles.positionOption}>
                    <PanelTop aria-hidden="true" size={15} />
                    {i18n('setting.terminal.openPositionTab')}
                  </span>
                ),
              },
              {
                value: 'bottom',
                label: (
                  <span className={styles.positionOption}>
                    <PanelBottom aria-hidden="true" size={15} />
                    {i18n('setting.terminal.openPositionBottom')}
                  </span>
                ),
              },
              {
                value: 'right',
                label: (
                  <span className={styles.positionOption}>
                    <PanelRight aria-hidden="true" size={15} />
                    {i18n('setting.terminal.openPositionRight')}
                  </span>
                ),
              },
            ]}
            onChange={(openPosition) =>
              updateTerminalSettings({ openPosition: openPosition as TerminalOpenPosition })
            }
          />
          <div className={styles.hint}>{i18n('setting.terminal.openPositionApplyHint')}</div>
        </div>
      </section>

      <section
        className={styles.settingRow}
        data-setting-group="close-confirmation"
        data-setting-search-id="terminal.confirmBeforeClose"
      >
        <div className={styles.settingMeta}>
          <ShieldCheck aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.terminal.confirmBeforeClose')}
            </div>
            <div className={styles.settingDescription}>
              {i18n('setting.terminal.confirmBeforeCloseDescribe')}
            </div>
          </div>
        </div>
        <div className={styles.switchControl}>
          <Switch
            aria-label={i18n('setting.terminal.confirmBeforeClose')}
            checked={terminalSettings.confirmBeforeClose}
            onChange={(confirmBeforeClose) => updateTerminalSettings({ confirmBeforeClose })}
          />
        </div>
      </section>

      <section className={styles.settingRow} data-setting-group="shell" data-setting-search-id="terminal.shell">
        <div className={styles.settingMeta}>
          <Terminal aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.terminal.shell')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.terminal.shellDescribe')}</div>
          </div>
        </div>
        <div className={styles.settingControl}>
          {loading ? (
            <div className={styles.controlState}>
              <Spin size="small" />
            </div>
          ) : loadFailed ? (
            <Alert
              className={styles.capabilitiesAlert}
              type="warning"
              showIcon
              message={i18n('setting.terminal.capabilitiesFailed')}
            />
          ) : (
            <Select
              aria-label={i18n('setting.terminal.shell')}
              value={terminalSettings.shellId}
              className={styles.shellSelect}
              suffixIcon={<ChevronDown size={14} />}
              options={shells.map((shell) => ({ value: shell.id, label: shell.label }))}
              onChange={(shellId: TerminalShellId) => updateTerminalSettings({ shellId })}
            />
          )}
          <div className={styles.hint}>{i18n('setting.terminal.shellApplyHint')}</div>
        </div>
      </section>

      <section className={styles.settingRow} data-setting-group="theme" data-setting-search-id="terminal.theme">
        <div className={styles.settingMeta}>
          <Palette aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.terminal.theme')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.terminal.themeDescribe')}</div>
          </div>
        </div>
        <div className={styles.settingControl}>
          <div aria-label={i18n('setting.terminal.theme')} className={styles.themeGrid} role="group">
            {themeOptions.map((config) => {
              const isActive = terminalSettings.themeId === config.id;
              const colors = [
                config.theme.red,
                config.theme.yellow,
                config.theme.green,
                config.theme.cyan,
                config.theme.blue,
                config.theme.magenta,
              ];
              return (
                <button
                  aria-pressed={isActive}
                  key={config.id}
                  type="button"
                  className={cx(styles.themeOption, {
                    [styles.activeThemeOption]: isActive,
                  })}
                  onClick={() => updateTerminalSettings({ themeId: config.id as TerminalThemeId })}
                >
                  <span
                    className={cx(styles.themePreview, {
                      [styles.activeThemePreview]: isActive,
                    })}
                    style={{
                      backgroundColor: config.theme.background,
                      color: config.theme.foreground,
                    }}
                  >
                    {isActive ? (
                      <span className={styles.themeCheck}>
                        <Check aria-hidden="true" size={13} strokeWidth={2.2} />
                      </span>
                    ) : null}
                    <span className={styles.colorRow}>
                      {colors.map((color, index) => (
                        <span key={`${config.id}-${index}`} style={{ backgroundColor: color }} />
                      ))}
                    </span>
                    <span className={styles.commandPreview}>
                      <span style={{ color: config.theme.green }}>$</span> npm run dev
                    </span>
                  </span>
                  <span className={styles.themeName}>{config.name}</span>
                </button>
              );
            })}
          </div>
          <div className={styles.hint}>{i18n('setting.terminal.themeApplyHint')}</div>
        </div>
      </section>
    </div>
  );
}
