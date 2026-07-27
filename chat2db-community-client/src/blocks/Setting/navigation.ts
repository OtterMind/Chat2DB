export const SETTING_NAVIGATION_GROUPS = ['general', 'services', 'account', 'information'] as const;

export type SettingNavigationGroupCode = (typeof SETTING_NAVIGATION_GROUPS)[number];

export interface GroupableSettingMenuItem {
  group: SettingNavigationGroupCode;
}

export interface SettingNavigationGroup<T> {
  code: SettingNavigationGroupCode;
  items: T[];
}

export function groupSettingMenuItems<T extends GroupableSettingMenuItem>(items: T[]): SettingNavigationGroup<T>[] {
  return SETTING_NAVIGATION_GROUPS.map((code) => ({
    code,
    items: items.filter((item) => item.group === code),
  })).filter((group) => group.items.length > 0);
}
