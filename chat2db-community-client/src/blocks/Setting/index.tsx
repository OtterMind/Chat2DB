import PurchaseDetails from '@/components/PurchaseDetails';
import i18n from '@/i18n';
import {
  BadgePlus,
  ClipboardPen,
  Info,
  Keyboard,
  MonitorCheck,
  ReceiptText,
  ShieldCheck,
  SlidersHorizontal,
  UserRound,
} from 'lucide-react';
import { useEffect, useMemo } from 'react';
import About from './About';
import BaseSetting from './BaseSetting';
import EditorSetting from './EditorSetting';
import Invite from './Invite';
import License from './License';
import McpSetting from './McpSetting';
import NetworkProxySetting from './NetworkProxySetting';
import Personal from './Personal';
import SettingLayout, { type SettingMenuItem } from './SettingLayout';

// ---- store -----
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { useGlobalStore } from '@/store/global';
import DeviceCer from './DeviceCer';
import ShortcutSetting from './ShortcutSetting';

function Setting() {
  const {
    settingPageActiveTab = 'basic',
    setSettingPageActiveTab,
    language,
    isCN,
  } = useGlobalStore((state) => {
    return {
      settingPageActiveTab: state.settingPageActiveTab,
      setSettingPageActiveTab: state.setSettingPageActiveTab,
      language: state.baseSetting.language,
      isCN: state.appConfig.isCN,
    };
  });

  const menusList = useMemo(() => {
    if (runtimeEditionConfig.settingMenuProfile === 'community') {
      return [
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
      ] satisfies SettingMenuItem[];
    }

    if (runtimeEditionConfig.settingMenuProfile === 'local') {
      return [
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
        ...(runtimeEditionConfig.licenseSetting
          ? [
              {
                title: i18n('setting.license.title'),
                describe: i18n('setting.license.titleDes'),
                group: 'account' as const,
                icon: ShieldCheck,
                body: <License />,
                code: 'license',
              },
            ]
          : []),
        {
          title: i18n('setting.nav.aboutUs'),
          describe: i18n('setting.nav.aboutUsDescribe'),
          group: 'information' as const,
          hidePageHeader: true,
          icon: Info,
          body: <About />,
          code: 'about',
        },
      ] satisfies SettingMenuItem[];
    }

    const list = [
      {
        title: i18n('setting.nav.basic'),
        describe: i18n('setting.nav.basicDescribe'),
        group: 'general' as const,
        icon: SlidersHorizontal,
        body: <BaseSetting />,
        code: 'basic',
      },
      {
        title: i18n('setting.nav.personal'),
        describe: i18n('setting.nav.personalDescribe'),
        group: 'account' as const,
        icon: UserRound,
        body: <Personal />,
        code: 'personal',
      },
      {
        title: i18n('setting.nav.editSetting'),
        describe: i18n('setting.nav.editSettingDescribe'),
        group: 'general' as const,
        icon: ClipboardPen,
        body: <EditorSetting />,
        code: 'editSetting',
      },
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
      // {
      //   title: i18n('setting.nav.apiKeys'),
      //   describe: '',
      //   iconCode: 'icon-apikeys',
      //   body: <ApiKeys />,
      //   code: 'apiKeys',
      // },
      {
        title: i18n('invite.setting.nav.title'),
        describe: i18n('invite.setting.titleDes'),
        group: 'account' as const,
        icon: BadgePlus,
        body: <Invite />,
        code: 'invite',
      },
      {
        title: i18n('setting.purchaseDetails.title'),
        describe: '',
        group: 'account' as const,
        icon: ReceiptText,
        body: <PurchaseDetails hideTitle />,
        code: 'purchase',
      },
      {
        title: i18n('license.deviceCertificateTitle'),
        describe: '',
        group: 'account' as const,
        icon: MonitorCheck,
        body: <DeviceCer />,
        code: 'deviceCer',
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
    ] satisfies SettingMenuItem[];

    return list;
  }, [language, isCN]);

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
