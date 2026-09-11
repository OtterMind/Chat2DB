import i18n from '@/i18n';
import { IconfontSvg } from '@chat2db/ui';
import SearchBar, { type SearchBarRef } from '@/components/SearchBar';
import { X, type LucideIcon } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { history } from 'umi';
import { groupSettingMenuItems, type SettingNavigationGroupCode } from './navigation';
import {
  getSettingTargetScrollTop,
  isSettingsSearchShortcut,
  searchSettings,
  type SettingSearchResult,
} from './search';
import { getSettingSearchItems } from './searchCatalog';
import { useStyles } from './style';

interface SettingMenuItemBase {
  body: ReactNode;
  code: string;
  describe: string;
  group: SettingNavigationGroupCode;
  hidePageHeader?: boolean;
  title: string;
}

type SettingMenuIcon =
  | { icon: LucideIcon; iconCode?: never }
  | { icon?: never; iconCode: string };

export type SettingMenuItem = SettingMenuItemBase & SettingMenuIcon;

interface SettingLayoutProps {
  activeTab: string | false;
  menus: SettingMenuItem[];
  onActiveTabChange: (tab: string | false) => void;
}

type I18nKey = Parameters<typeof i18n>[0];

const groupTitleKeys: Record<SettingNavigationGroupCode, I18nKey> = {
  general: 'setting.nav.group.general',
  services: 'setting.nav.group.services',
  account: 'setting.nav.group.account',
  information: 'setting.nav.group.information',
};

export default function SettingLayout({ activeTab, menus, onActiveTabChange }: SettingLayoutProps) {
  const { styles, cx } = useStyles();
  const settingBoxRef = useRef<HTMLDivElement>(null);
  const menuContentRef = useRef<HTMLElement>(null);
  const searchBarRef = useRef<SearchBarRef>(null);
  const highlightedSearchTargetRef = useRef<HTMLElement | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedSearchResultKey, setSelectedSearchResultKey] = useState<string | null>(null);
  const [pendingSearchTarget, setPendingSearchTarget] = useState<{
    menuCode: string;
    targetId: string;
  } | null>(null);
  const menuGroups = groupSettingMenuItems(menus);
  const activeMenu = menus.find((item) => item.code === activeTab) ?? menus[0];
  const searchableMenus = useMemo(
    () => menus.map((menu) => ({ ...menu, searchItems: getSettingSearchItems(menu.code) })),
    [menus],
  );
  const searchResults = useMemo(() => searchSettings(searchableMenus, searchQuery), [searchQuery, searchableMenus]);
  const isSearching = !!searchQuery.trim();

  useEffect(() => {
    const focusSettingsSearch = (event: KeyboardEvent) => {
      if (!isSettingsSearchShortcut(event)) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      searchBarRef.current?.focus();
    };

    document.addEventListener('keydown', focusSettingsSearch, true);
    return () => document.removeEventListener('keydown', focusSettingsSearch, true);
  }, []);

  useEffect(
    () => () => {
      highlightedSearchTargetRef.current?.removeAttribute('data-setting-search-highlighted');
    },
    [],
  );

  useEffect(() => {
    if (!pendingSearchTarget || activeTab !== pendingSearchTarget.menuCode) {
      return;
    }

    let targetFrame = 0;
    const pageFrame = window.requestAnimationFrame(() => {
      targetFrame = window.requestAnimationFrame(() => {
        const target = settingBoxRef.current?.querySelector<HTMLElement>(
          `[data-setting-search-id="${pendingSearchTarget.targetId}"]`,
        );
        if (target) {
          if (target.dataset.settingSearchExpandable === 'true' && target.getAttribute('aria-expanded') === 'false') {
            target.click();
          }
          highlightedSearchTargetRef.current?.removeAttribute('data-setting-search-highlighted');
          target.setAttribute('data-setting-search-highlighted', 'true');
          highlightedSearchTargetRef.current = target;
          const menuContent = menuContentRef.current;
          if (menuContent) {
            const menuContentRect = menuContent.getBoundingClientRect();
            const targetRect = target.getBoundingClientRect();
            menuContent.scrollTo({
              behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
              top: getSettingTargetScrollTop({
                containerHeight: menuContent.clientHeight,
                containerTop: menuContentRect.top,
                scrollTop: menuContent.scrollTop,
                targetHeight: targetRect.height,
                targetTop: targetRect.top,
              }),
            });
          }
        }
        setPendingSearchTarget(null);
      });
    });

    return () => {
      window.cancelAnimationFrame(pageFrame);
      if (targetFrame) {
        window.cancelAnimationFrame(targetFrame);
      }
    };
  }, [activeTab, pendingSearchTarget]);

  function closeSettings() {
    const pathName = window.location.pathname.split('/')[1];
    onActiveTabChange(false);
    if (pathName === 'settings') {
      history.push('/');
    }
  }

  function openSearchResult(result: SettingSearchResult) {
    highlightedSearchTargetRef.current?.removeAttribute('data-setting-search-highlighted');
    highlightedSearchTargetRef.current = null;
    setSelectedSearchResultKey(result.key);
    onActiveTabChange(result.menuCode);
    setPendingSearchTarget(
      result.targetId
        ? {
            menuCode: result.menuCode,
            targetId: result.targetId,
          }
        : null,
    );
  }

  function updateSearchQuery(value: string) {
    highlightedSearchTargetRef.current?.removeAttribute('data-setting-search-highlighted');
    highlightedSearchTargetRef.current = null;
    setPendingSearchTarget(null);
    setSelectedSearchResultKey(null);
    setSearchQuery(value);
  }

  return (
    <div className={styles.settingBox} ref={settingBoxRef}>
      <header className={styles.header}>
        <div className={styles.headerTitle}>{i18n('setting.title.setting')}</div>
        <button
          aria-label={i18n('common.button.close')}
          className={styles.headerClose}
          onClick={closeSettings}
          title={i18n('common.button.close')}
          type="button"
        >
          <X aria-hidden="true" size={18} strokeWidth={1.8} />
        </button>
      </header>
      <div className={styles.content}>
        <nav aria-label={i18n('setting.title.setting')} className={styles.left}>
          <div className={styles.searchWrap}>
            <SearchBar
              aria-label={i18n('common.text.search')}
              className={styles.searchBar}
              onChange={(event) => updateSearchQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape') {
                  updateSearchQuery('');
                }
              }}
              placeholder={i18n('common.text.search')}
              ref={searchBarRef}
              value={searchQuery}
            />
          </div>
          <div className={cx(styles.navContent, { [styles.navContentSearch]: isSearching })}>
            {isSearching ? (
              searchResults.length ? (
                searchResults.map((result) => {
                  const menu = menus.find((item) => item.code === result.menuCode);
                  if (!menu) {
                    return null;
                  }
                  const isSelected = selectedSearchResultKey === result.key;
                  return (
                    <button
                      aria-current={isSelected ? 'location' : undefined}
                      className={cx(styles.searchResult, { [styles.searchResultActive]: isSelected })}
                      key={result.key}
                      onClick={() => openSearchResult(result)}
                      type="button"
                    >
                      <SettingMenuIcon className={styles.navItemIcon} item={menu} />
                      <span className={styles.searchResultText}>
                        <span className={styles.searchResultTitle} data-setting-search-result-title="true">
                          {result.title}
                        </span>
                        {result.title !== result.menuTitle ? (
                          <span className={styles.searchResultPage}>{result.menuTitle}</span>
                        ) : null}
                      </span>
                    </button>
                  );
                })
              ) : (
                <div className={styles.searchEmpty}>{i18n('common.text.noData')}</div>
              )
            ) : (
              menuGroups.map((group) => {
                const groupLabelId = `setting-navigation-${group.code}`;
                return (
                  <div aria-labelledby={groupLabelId} className={styles.navGroup} key={group.code} role="group">
                    <div className={styles.navGroupLabel} id={groupLabelId}>
                      {i18n(groupTitleKeys[group.code])}
                    </div>
                    <div className={styles.navGroupItems}>
                      {group.items.map((item) => {
                        const isActive = activeTab === item.code;
                        return (
                          <button
                            aria-current={isActive ? 'page' : undefined}
                            className={cx(styles.navItem, { [styles.navItemActive]: isActive })}
                            key={item.code}
                            onClick={() => onActiveTabChange(item.code)}
                            type="button"
                          >
                            <SettingMenuIcon className={styles.navItemIcon} item={item} />
                            <span>{item.title}</span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </nav>
        <main
          aria-label={activeMenu?.hidePageHeader ? activeMenu.title : undefined}
          aria-labelledby={activeMenu?.hidePageHeader ? undefined : 'setting-page-title'}
          className={styles.menuContent}
          ref={menuContentRef}
        >
          <div
            className={cx(styles.menuContentInner, {
              [styles.menuContentInnerNoHeader]: activeMenu?.hidePageHeader,
            })}
          >
            {activeMenu?.hidePageHeader ? null : (
              <div className={styles.pageHeader}>
                <h1 className={styles.pageTitle} id="setting-page-title">
                  {activeMenu?.title}
                </h1>
                {activeMenu?.describe ? <p className={styles.pageDescription}>{activeMenu.describe}</p> : null}
              </div>
            )}
            <div className={styles.pageBody}>{activeMenu?.body}</div>
          </div>
        </main>
      </div>
    </div>
  );
}

function SettingMenuIcon({ className, item }: { className: string; item: SettingMenuItem }) {
  if (item.iconCode) {
    return <IconfontSvg aria-hidden="true" className={className} code={item.iconCode} size={18} />;
  }

  const MenuIcon = item.icon;
  return <MenuIcon aria-hidden="true" className={className} size={18} strokeWidth={1.8} />;
}
