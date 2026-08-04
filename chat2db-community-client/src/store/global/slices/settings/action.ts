import { EditorSettings } from '@/components/SQLEditor';
import { APP_URL_CONFIG_CHINA, APP_URL_CONFIG_OVERSEAS } from '@/constants/appConfig';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import {
  EffectiveShortcutConfig,
  getEffectiveShortcutConfigMap,
  getShortcutOverrideValue,
  normalizeShortcutBinding,
  ShortcutAction,
} from '@/constants/shortcut';
import i18n from '@/i18n';
import aiService from '@/service/ai';
import configService from '@/service/config';
import oauthService from '@/service/enterprise/oauth';
import miscServices from '@/service/misc';
import { AIType, IAIConfig, IRemainingUse } from '@/typings/ai';
import {
  DataTableSettings,
  GlobalAISettings,
  GlobalBaseSettings,
  IUpdateDetail,
  ShortcutOverrides,
  TerminalSettings,
} from '@/typings/settings';
import { getSystemThemeMode } from '@/utils/color';
import { isDesktop, isDesktopEnv, isOfflineEnv } from '@/utils/env';
import { deepEqual } from '@/utils/equal';
import { deepMerge } from '@/utils/merge';
import { getMacAddress } from '@/utils/os';
import { openWebPage } from '@/utils/url';
import { staticMessage, ThemeAppearance } from '@chat2db/ui';
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

  // ====================== AISetting ======================
  /**
   * Set AI configuration
   */
  setAISetting: (settings: DeepPartial<GlobalAISettings>) => void;
  /**
   * Set AI model related configurations
   */
  setAIConfig: (aiConfig: Partial<IAIConfig>) => void;
  /**
   * Set configuration related to user remaining times
   */
  setRemainUse: (remainingUse?: Partial<IRemainingUse>) => void;
  /**
   * Set whether to enable whitelist
   */
  setAIWithWhite: (hasWhite: boolean) => void;
  /**
   * Get AI model configuration
   */
  fetchAIConfig: () => void;
  /**
   * Update AI model configuration
   */
  updateAIConfig: (aiConfig: Partial<IAIConfig>) => void;
  /**
   * Update user remaining number configuration
   */
  updateRemainingUse: (apiKey?: string) => void;
  /**
   * Update AI whitelist
   */
  updateAIWithWhite: (apiKey?: string) => void;
  // ====================== AIConfig ======================

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
  // ====================== AISetting ======================
  setAISetting: (aiSettings) => {
    const prevAISetting = get().aiSettings;
    const nextAISetting = deepMerge(prevAISetting, aiSettings) as GlobalAISettings;
    if (deepEqual(prevAISetting, nextAISetting)) return;
    set({
      aiSettings: nextAISetting,
    });
  },
  setAIConfig: (aiConfig) => {
    get().setAISetting({ aiConfig });
  },
  setRemainUse: (remainingUse) => {
    get().setAISetting({ remainingUse });
  },
  setAIWithWhite: (hasWhite) => {
    get().setAISetting({ hasWhite });
  },
  fetchAIConfig: () => {
    configService.getAISystemConfig({}).then((res) => {
      get().setAIConfig(res);
      if (res?.aiSqlSource === AIType.CHAT2DBAI && res.apiKey) {
        get().updateAIWithWhite(res.apiKey);
      }
    });
  },
  updateAIConfig: (aiConfig) => {
    configService.setAISystemConfig(aiConfig).then(() => {
      staticMessage.success(i18n('common.text.submittedSuccessfully'));
      get().setAIConfig(aiConfig);
    });
    if (aiConfig?.aiSqlSource === AIType.CHAT2DBAI) {
      get().updateAIWithWhite(aiConfig?.apiKey);
    } else {
      get().setAIWithWhite(false);
    }
  },
  updateAIWithWhite: (apiKey) => {
    configService.getAIWhiteAccess({ apiKey: apiKey ?? '' }).then((hasWhite) => {
      get().setAIWithWhite(hasWhite);
    });
  },
  updateRemainingUse: (apiKey) => {
    const aiSqlSource = get().aiSettings.aiConfig.aiSqlSource;
    if (!apiKey || aiSqlSource !== AIType.CHAT2DBAI) {
      get().setRemainUse(undefined);
      return;
    }
    aiService.getRemainingUse().then((res) => {
      get().setRemainUse(res);
    });
  },
  setUpdateDetail: (updateDetail) => {
    set({
      updateDetail: produce(get().updateDetail, (draft) => {
        Object.assign(draft, updateDetail);
      }),
    });
  },
  queryAppConfig: () => {
    if (!runtimeEditionConfig.remoteAppConfig && runtimeEditionConfig.localAppConfig) {
      set({
        appConfig: produce(get().appConfig, (draft) => {
          Object.assign(draft, runtimeEditionConfig.localAppConfig);
          if (runtimeEditionConfig.localAppUrlConfig) {
            get().setAppUrlConfig(runtimeEditionConfig.localAppUrlConfig);
          }
          draft.version = __APP_VERSION__;
          draft.isReady = true;
        }),
      });
      return;
    }

    oauthService.getAppConfig().then((res) => {
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

    miscServices.fetchSpm({
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
