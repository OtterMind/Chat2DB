import type { SettingMenuItem } from '@/blocks/Setting/SettingLayout';

export function appendClientSettingMenuItems(
  sharedItems: readonly SettingMenuItem[],
  editionItems: readonly SettingMenuItem[],
): SettingMenuItem[] {
  const codes = new Set(sharedItems.map((item) => item.code));
  for (const item of editionItems) {
    if (codes.has(item.code)) {
      throw new Error(`Client setting contribution cannot replace shared item: ${item.code}`);
    }
    codes.add(item.code);
  }
  return [...sharedItems, ...editionItems];
}
