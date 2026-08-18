import type { SettingMenuProfile } from '@/constants/runtimeEdition';
import type { LangType } from '@/constants/settings';
import type { SettingMenuItem } from '@/blocks/Setting/SettingLayout';

export interface EditionSettingMenuContext {
  language: LangType;
  profile: SettingMenuProfile;
}

export interface EditionUiExtension {
  settingMenuItems?: (context: EditionSettingMenuContext) => readonly SettingMenuItem[];
}
