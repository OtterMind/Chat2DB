import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react';
import { IconButton } from '@chat2db/ui';
import { Search } from 'lucide-react';

import { PANEL_TOOLBAR_BUTTON_SIZE } from '@/components/PanelToolbar';
import SearchBar, { type SearchBarRef } from '@/components/SearchBar';
import {
  ShortcutAction,
  type ShortcutOverrides,
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
} from '@/constants/shortcut';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';

import { useStyles } from './style';

interface WorkspaceHeaderSearchProps {
  active?: boolean;
  matchCount?: number;
  onChange: (value: string) => void;
  onClose?: () => void;
  value: string;
}

const WorkspaceHeaderSearch = ({
  active = true,
  matchCount,
  onChange,
  onClose,
  value,
}: WorkspaceHeaderSearchProps) => {
  const [expanded, setExpanded] = useState(() => Boolean(value));
  const searchRef = useRef<HTMLDivElement>(null);
  const searchBarRef = useRef<SearchBarRef>(null);
  const { styles, cx } = useStyles();
  const shortcutOverrides = useGlobalStore((state) => state.shortcutOverrides);
  const shortcutConfig = useMemo(
    () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
    [shortcutOverrides],
  );

  const openSearch = useCallback(() => {
    setExpanded(true);
    window.requestAnimationFrame(() => searchBarRef.current?.focus());
  }, []);

  const closeSearch = useCallback(() => {
    setExpanded(false);
    onChange('');
    onClose?.();
  }, [onChange, onClose]);

  useEffect(() => {
    if (!expanded) {
      return;
    }

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target;
      const clickedTree = target instanceof Element && target.closest('.ant-tree');
      if (!searchRef.current?.contains(target as Node) && !clickedTree) {
        closeSearch();
      }
    };

    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [closeSearch, expanded]);

  useEffect(() => {
    if (!active) {
      return;
    }
    const searchArea = document.getElementById('tree-search-area');
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (isShortcutEventMatch(event, shortcutConfig[ShortcutAction.WorkspaceTreeSearch].binding)) {
        event.preventDefault();
        openSearch();
      }
    };

    searchArea?.addEventListener('keydown', handleKeyDown);
    return () => searchArea?.removeEventListener('keydown', handleKeyDown);
  }, [active, openSearch, shortcutConfig]);

  if (!expanded) {
    return (
      <IconButton
        className={styles.iconButton}
        size={PANEL_TOOLBAR_BUTTON_SIZE}
        title={i18n('common.text.search')}
        tooltipPlacement="bottom"
        icon={Search}
        onClick={openSearch}
      />
    );
  }

  return (
    <div ref={searchRef} className={cx(styles.search, styles.searchExpanded)}>
      <SearchBar
        ref={searchBarRef}
        className={styles.searchBar}
        placeholder={i18n('common.text.search')}
        value={value}
        onChange={(event: ChangeEvent<HTMLInputElement>) => onChange(event.target.value)}
        onKeyDown={(event: KeyboardEvent<HTMLInputElement>) => {
          if (event.key === 'Escape') {
            event.stopPropagation();
            closeSearch();
          }
        }}
        suffix={
          <span className={styles.searchMatchCount}>{value && matchCount !== undefined ? matchCount : null}</span>
        }
      />
    </div>
  );
};

export default WorkspaceHeaderSearch;
