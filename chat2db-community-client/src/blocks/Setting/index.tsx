import clientExtension from '@/client-extension';
import { appendClientSettingMenuItems } from '@/client-extension/settingMenus';
import i18n from '@/i18n';
import { ClipboardPen, Info, Keyboard, SlidersHorizontal, Terminal } from 'lucide-react';
import { useEffect, useMemo } from 'react';
import About from './About';
import BaseSetting from './BaseSetting';
import EditorSetting from './EditorSetting';
import McpSetting from './McpSetting';
import NetworkProxySetting from './NetworkProxySetting';
import SettingLayout, { type SettingMenuItem } from './SettingLayout';

// ---- store -----
import { clientRuntime } from '@client-runtime';
import { useGlobalStore } from '@/store/global';
import { isDesktop } from '@/utils/env';
import ShortcutSetting from './ShortcutSetting';
import TerminalSetting from './TerminalSetting';

function Setting() {
  const {
    settingPageActiveTab = 'basic',
    setSettingPageActiveTab,
    language,
  } = useGlobalStore((state) => {
    return {
      settingPageActiveTab: state.settingPageActiveTab,
      setSettingPageActiveTab: state.setSettingPageActiveTab,
      language: state.baseSetting.language,
    };
  });

  const menusList = useMemo(() => {
    const sharedItems = [
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
      ...(isDesktop
        ? [
            {
              title: i18n('setting.nav.terminal'),
              describe: i18n('setting.nav.terminalDescribe'),
              group: 'general' as const,
              icon: Terminal,
              body: <TerminalSetting />,
              code: 'terminal',
            },
          ]
        : []),
      ...(clientRuntime.showMcpSetting
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
      ...(clientRuntime.showNetworkProxySetting
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
    ] satisfies SettingMenuItem[];

    const informationItems = [
      {
        title: i18n('setting.nav.aboutUs'),
        describe: i18n('setting.nav.aboutUsDescribe'),
        group: 'information' as const,
        hidePageHeader: true,
        icon: Info,
        body: clientExtension.settings?.about ?? <About />,
        code: 'about',
      },
    ] satisfies SettingMenuItem[];

    const clientItems = clientExtension.settings?.items?.({ language }) ?? [];

    return appendClientSettingMenuItems([...sharedItems, ...informationItems], clientItems);
  }, [language]);

  useEffect(() => {
    if (settingPageActiveTab && !menusList.some((t) => t.code === settingPageActiveTab)) {
      setSettingPageActiveTab('basic');
    }
  }, [menusList, settingPageActiveTab, setSettingPageActiveTab]);

  return (
    <SettingLayout activeTab={settingPageActiveTab} menus={menusList} onActiveTabChange={setSettingPageActiveTab} />
  );
}

export default Setting;
