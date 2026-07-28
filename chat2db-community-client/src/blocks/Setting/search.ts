export interface SettingSearchItem {
  keywords?: readonly string[];
  targetId?: string;
  title: string;
}

export interface SearchableSettingMenu {
  code: string;
  describe: string;
  searchItems?: readonly SettingSearchItem[];
  title: string;
}

export interface SettingSearchResult {
  key: string;
  menuCode: string;
  menuTitle: string;
  targetId?: string;
  title: string;
}

interface SettingTargetScrollMetrics {
  containerHeight: number;
  containerTop: number;
  scrollTop: number;
  targetHeight: number;
  targetTop: number;
}

function normalizeSearchText(value: string) {
  return value
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase()
    .trim();
}

function matchesSearch(values: readonly string[], terms: readonly string[]) {
  const haystack = normalizeSearchText(values.filter(Boolean).join(' '));
  return terms.every((term) => haystack.includes(term));
}

export function searchSettings(menus: readonly SearchableSettingMenu[], query: string): SettingSearchResult[] {
  const terms = normalizeSearchText(query)
    .split(/\s+/)
    .filter(Boolean);
  if (!terms.length) {
    return [];
  }

  const results: SettingSearchResult[] = [];

  for (const menu of menus) {
    if (matchesSearch([menu.title, menu.describe, menu.code], terms)) {
      results.push({
        key: `${menu.code}:page`,
        menuCode: menu.code,
        menuTitle: menu.title,
        title: menu.title,
      });
    }

    for (const [itemIndex, item] of (menu.searchItems ?? []).entries()) {
      if (!matchesSearch([item.title, ...(item.keywords ?? [])], terms)) {
        continue;
      }
      results.push({
        key: `${menu.code}:${item.targetId ?? 'item'}:${itemIndex}`,
        menuCode: menu.code,
        menuTitle: menu.title,
        targetId: item.targetId,
        title: item.title,
      });
    }
  }

  return results;
}

type SettingsSearchShortcutEvent = Pick<KeyboardEvent, 'altKey' | 'code' | 'ctrlKey' | 'metaKey' | 'shiftKey'>;

export function isSettingsSearchShortcut(event: SettingsSearchShortcutEvent) {
  const hasSingleCommandModifier = (event.metaKey || event.ctrlKey) && !(event.metaKey && event.ctrlKey);
  return event.code === 'KeyF' && hasSingleCommandModifier && !event.altKey && !event.shiftKey;
}

export function getSettingTargetScrollTop({
  containerHeight,
  containerTop,
  scrollTop,
  targetHeight,
  targetTop,
}: SettingTargetScrollMetrics) {
  const targetOffset = scrollTop + targetTop - containerTop;
  return Math.max(0, targetOffset - (containerHeight - targetHeight) / 2);
}
