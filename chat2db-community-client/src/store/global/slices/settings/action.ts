import type { EditorSettings } from '@/components/SQLEditor/type';
import { APP_URL_CONFIG_CHINA, APP_URL_CONFIG_OVERSEAS } from '@/constants/appConfig';
import { clientRuntime } from '@client-runtime';
import {
  EffectiveShortcutConfig,
  getEffectiveShortcutConfigMap,
  getShortcutOverrideValue,
  normalizeShortcutBinding,
  ShortcutAction,
} from '@/constants/shortcut';
import appConfigService from '@/service/appConfig';
import {
  DataTableSettings,
  GlobalBaseSettings,
  IUpdateDetail,
  ShortcutOverrides,
  TerminalSettings,
} from '@/typings/settings';
import { getSystemThemeMode } from '@/utils/color';
import { isDesktop, isDesktopEnv, isOfflineEnv } from '@/utils/env';
import { getMacAddress } from '@/utils/os';
import { openWebPage } from '@/utils/url';
import { ThemeAppearance } from '@chat2db/ui';
import { produce } from 'immer';
import { DeepPartial } from 'utility-types';
import type { StateCreator } from 'zustand/vanilla';
import { GlobalStore } from '../../store';

export interface SettingsAction {
  // ====================== BaseSetting ======================
  /**
   * Set basic configuration
   */
  setBaseSetting: (setting: DeepPartial<GlobalBaseSettings>) => void;
  /**
   * Set the current specific theme color
   */
  setAppearance: (appearance: GlobalBaseSettings['appearance']) => void;
  /**
   * Set theme color
   */
  setPrimaryColor: (primaryColor: GlobalBaseSettings['primaryColor']) => void;
  /**
   * Set neutral colors
   */
  setNeutralColor: (neutralColor: GlobalBaseSettings['neutralColor']) => void;
  /**
   * Set language
   */
  setLanguage: (language: GlobalBaseSettings['language']) => void;
  /**
   * Set custom font
   */
  setCustomFont: (customFont: GlobalBaseSettings['customFont']) => void;
  /**
   * Set custom font size
   */
  setCustomFontSize: (customFontSize: GlobalBaseSettings['customFontSize']) => void;

  /**
   * Update Update status
   */
  setUpdateDetail: (updatedStatus: IUpdateDetail) => void;
  // Get the APP configuration of the server
  queryAppConfig: () => void;
  // ====================== EditorSettings ======================
  updateEditorSettings: (editorSettings: EditorSettings) => void;
  getEditorTheme: (appearance: any) => EditorSettings['theme'];
  /**
   * RBI
   */
  fetchSpm: () => void;

  /**
   * Get shortcut key configuration
   */
  getShortcutConfig: () => Record<ShortcutAction, EffectiveShortcutConfig>;

  /**
   * Update shortcut key configuration
   */
  updateShortcutConfig: (key: ShortcutAction, value: string | null) => void;

  /**
   * Reset individual shortcut keys
   */
  resetShortcutConfig: (key: ShortcutAction) => void;

  /**
   * Reset all shortcut keys
   */
  resetAllShortcutConfig: () => void;

  /**
   * Update table settings
   */
  updateDataTableSettings: (dataTableSettings: DataTableSettings) => void;
  updateTerminalSettings: (terminalSettings: Partial<TerminalSettings>) => void;
}

export const createSettingsAction: StateCreator<GlobalStore, [['zustand/devtools', never]], [], SettingsAction> = (
  set,
  get,
) => ({
  // ====================== BaseSetting ======================
  setBaseSetting: (baseSetting) => {
    set({
      baseSetting: produce(get().baseSetting, (draft) => {
        Object.assign(draft, baseSetting);
      }),
    });
  },
  setAppearance: (appearance) => {
    get().setBaseSetting({ appearance });
  },
  setPrimaryColor: (primaryColor) => {
    get().setBaseSetting({ primaryColor });
  },
  setNeutralColor: (neutralColor) => {
    get().setBaseSetting({ neutralColor });
  },
  setLanguage: (language) => {
    get().setBaseSetting({ language });
  },
  setCustomFont: (customFont) => {
    get().setBaseSetting({ customFont });
  },
  setCustomFontSize: (customFontSize) => {
    get().setBaseSetting({ customFontSize });
  },
  setUpdateDetail: (updateDetail) => {
    set({
      updateDetail: produce(get().updateDetail, (draft) => {
        Object.assign(draft, updateDetail);
      }),
    });
  },
  queryAppConfig: () => {
    if (!clientRuntime.loadAppConfigFromServer && clientRuntime.localAppConfig) {
      set({
        appConfig: produce(get().appConfig, (draft) => {
          Object.assign(draft, clientRuntime.localAppConfig);
          if (clientRuntime.localAppUrlConfig) {
            get().setAppUrlConfig(clientRuntime.localAppUrlConfig);
          }
          draft.version = __APP_VERSION__;
          draft.isReady = true;
        }),
      });
      return;
    }

    appConfigService.getAppConfig().then((res) => {
      set({
        appConfig: produce(get().appConfig, (draft) => {
          res?.countries?.forEach((item) => {
            // Determine whether it is a redirect
            if (item.redirect && !isOfflineEnv) {
              openWebPage(item.appUrl, '_self');
              return;
            }
            // Determine whether it is the current country
            if (item.current) {
              draft.curCountry = item;
              draft.appUrl = item.appUrl;
              // By default, the gatewayUrl in the startup parameters is taken.
              draft.gatewayUrl = __GATEWAY_URL__ || item.gatewayUrl;
              // Determine whether it is China
              draft.isCN = item.code === 'CN';
              get().setAppUrlConfig(draft.isCN ? APP_URL_CONFIG_CHINA : APP_URL_CONFIG_OVERSEAS);
            }
          }) || [];
          draft.countries = res?.countries;
          draft.version = __APP_VERSION__;
          draft.isReady = true;
        }),
      });
    });
  },
  updateEditorSettings: (editorSettings) => {
    const { theme } = editorSettings;
    const { appearance } = get().baseSetting;
    // Update the theme associated with the current appearance.
    if (appearance === 'light') {
      editorSettings.lightTheme = theme;
    } else if (appearance === 'dark') {
      editorSettings.darkTheme = theme;
    } else {
      editorSettings.darkDimmedTheme = theme;
    }
    set({
      editorSettings,
    });
  },
  getEditorTheme: (appearance) => {
    const { lightTheme, darkTheme, darkDimmedTheme } = get().editorSettings;

    switch (appearance) {
      case ThemeAppearance.Light:
        return lightTheme || 'vs';
      case ThemeAppearance.Dark:
        return darkTheme || 'vs-dark';
      case ThemeAppearance.Auto: {
        const systemTheme = getSystemThemeMode();
        if (systemTheme === 'light') {
          return lightTheme || 'vs';
        }
        return darkTheme || 'vs-dark';
      }
      case ThemeAppearance.DarkDimmed:
        return darkDimmedTheme || 'hc-black';
      default:
        return darkDimmedTheme || 'hc-black';
    }
  },
  fetchSpm: async () => {
    if (!isDesktopEnv || !isDesktop) {
      return;
    }

    const deviceUuid = await getMacAddress().catch(() => null);

    if (!deviceUuid) {
      return;
    }

    const { default: clientExtension } = await import('@client-extension');
    await clientExtension.reportClient?.({
      deviceUuid,
      clientVersion: get().appConfig.version,
      userAgent: window.navigator.userAgent,
    });
  },
  getShortcutConfig: () => {
    return getEffectiveShortcutConfigMap(get().shortcutOverrides as ShortcutOverrides);
  },
  updateShortcutConfig: (key: ShortcutAction, value: string | null) => {
    const override = getShortcutOverrideValue(key, normalizeShortcutBinding(value));
    set(
      produce(get(), (draft) => {
        if (override) {
          draft.shortcutOverrides[key] = override;
        } else {
          delete draft.shortcutOverrides[key];
        }
      }),
    );
  },
  resetShortcutConfig: (key: ShortcutAction) => {
    set(
      produce(get(), (draft) => {
        delete draft.shortcutOverrides[key];
      }),
    );
  },
  resetAllShortcutConfig: () => {
    set({
      shortcutOverrides: {},
    });
  },
  updateDataTableSettings: (dataTableSettings) => {
    set({
      dataTableSettings,
    });
  },
  updateTerminalSettings: (terminalSettings) => {
    set({
      terminalSettings: {
        ...get().terminalSettings,
        ...terminalSettings,
      },
    });
  },
});
