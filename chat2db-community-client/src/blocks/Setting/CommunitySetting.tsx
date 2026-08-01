import i18n from '@/i18n';
import { Bot, ClipboardPen, Info, Keyboard, SlidersHorizontal } from 'lucide-react';
import { useEffect, useMemo } from 'react';
import About from './About';
import BaseSetting from './BaseSetting';
import EditorSetting from './EditorSetting';
import McpSetting from './McpSetting';
import NetworkProxySetting from './NetworkProxySetting';
import SettingLayout, { type SettingMenuItem } from './SettingLayout';
import ShortcutSetting from './ShortcutSetting';
import SubscriptionAccountPanel from '@/blocks/AI/subscription/SubscriptionAccountPanel';
import { isSubscriptionManagementEntryVisible } from '@/blocks/AI/subscription/capability';

import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { useGlobalStore } from '@/store/global';
import { useSubscriptionAiStore } from '@/store/aiSubscription';
import { isCommunityEnv, isPackagedJcefDesktop } from '@/utils/env';

function CommunitySetting() {
  const {
    settingPageActiveTab = 'basic',
    setSettingPageActiveTab,
    language,
  } = useGlobalStore((state) => ({
    settingPageActiveTab: state.settingPageActiveTab,
    setSettingPageActiveTab: state.setSettingPageActiveTab,
    language: state.baseSetting.language,
  }));
  const {
    subscriptionHydrated,
    subscriptionCapability,
    subscriptionErrorCode,
    refreshSubscriptionSurface,
  } = useSubscriptionAiStore((state) => ({
    subscriptionHydrated: state.hydrated,
    subscriptionCapability: state.capability,
    subscriptionErrorCode: state.lastErrorCode,
    refreshSubscriptionSurface: state.refreshSurface,
  }));

  const subscriptionEntryVisible = isSubscriptionManagementEntryVisible({
    communityRuntime: isCommunityEnv,
    packagedJcefDesktop: isPackagedJcefDesktop(),
    hydrated: subscriptionHydrated,
    backendCapability: subscriptionCapability,
    lastErrorCode: subscriptionErrorCode,
  });

  useEffect(() => {
    if (isCommunityEnv && isPackagedJcefDesktop()) {
      void refreshSubscriptionSurface();
    }
  }, [refreshSubscriptionSurface]);

  const menusList = useMemo(
    () =>
      [
        {
          title: i18n('setting.nav.basic'),
          describe: i18n('setting.nav.basicDescribe'),
          group: 'general' as const,
          icon: SlidersHorizontal,
          body: <BaseSetting />,
          code: 'basic',
        },
        {
          title: i18n('setting.nav.editSetting'),
          describe: i18n('setting.nav.editSettingDescribe'),
          group: 'general' as const,
          icon: ClipboardPen,
          body: <EditorSetting />,
          code: 'editSetting',
        },
        ...(subscriptionEntryVisible
          ? [
              {
                title: i18n('ai.subscription.nav.title'),
                describe: i18n('ai.subscription.nav.describe'),
                group: 'services' as const,
                icon: Bot,
                body: <SubscriptionAccountPanel />,
                code: 'subscriptionAi',
              },
            ]
          : []),
        ...(runtimeEditionConfig.mcpSetting
          ? [
              {
                title: i18n('setting.nav.mcp'),
                describe: i18n('setting.text.mcpDescribe'),
                group: 'services' as const,
                iconCode: 'icon-mcp',
                body: <McpSetting />,
                code: 'mcp',
              },
            ]
          : []),
        ...(runtimeEditionConfig.networkProxySetting
          ? [
              {
                title: i18n('setting.nav.networkProxy'),
                describe: i18n('setting.text.networkProxyDescribe'),
                group: 'services' as const,
                iconCode: 'icon-wangluo',
                body: <NetworkProxySetting />,
                code: 'networkProxy',
              },
            ]
          : []),
        {
          title: i18n('setting.nav.shortcut'),
          describe: i18n('setting.nav.shortcutDescribe'),
          group: 'general' as const,
          icon: Keyboard,
          body: <ShortcutSetting />,
          code: 'shortcut',
        },
        {
          title: i18n('setting.nav.aboutUs'),
          describe: i18n('setting.nav.aboutUsDescribe'),
          group: 'information' as const,
          hidePageHeader: true,
          icon: Info,
          body: <About />,
          code: 'about',
        },
      ] satisfies SettingMenuItem[],
    [language, subscriptionEntryVisible],
  );

  useEffect(() => {
    if (settingPageActiveTab && !menusList.some((item) => item.code === settingPageActiveTab)) {
      setSettingPageActiveTab('basic');
    }
  }, [menusList, settingPageActiveTab, setSettingPageActiveTab]);

  return (
    <SettingLayout activeTab={settingPageActiveTab} menus={menusList} onActiveTabChange={setSettingPageActiveTab} />
  );
}

export default CommunitySetting;
