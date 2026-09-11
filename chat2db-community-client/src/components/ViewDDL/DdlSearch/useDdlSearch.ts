import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useGlobalStore } from '@/store/global';
import {
  ShortcutAction,
  ShortcutScope,
  ShortcutOverrides,
  getEffectiveShortcutConfig,
  isShortcutEventMatch,
} from '@/constants/shortcut';
import {
  DDL_SEARCH_STYLE_ATTRIBUTE,
  buildDdlSearchHighlightStyleText,
  computeCenteredScrollDelta,
  createMatchRanges,
  findDdlSearchMatches,
  findScrollContainer,
  getDdlSearchHighlightNames,
  isDdlSearchHighlightSupported,
  isRectFullyVisible,
  nextActiveIndex,
  nextDdlSearchInstanceId,
  pickVisibleRect,
  type DdlSearchMatch,
} from './ddlSearch';
import DdlSearchBar, { type DdlSearchBarRef } from './DdlSearchBar';

export interface UseDdlSearchOptions {
  /** Current DDL text. */
  sql: string;
  /** When this changes (DDL object switched), the search state resets. */
  resetKey?: unknown;
}

export interface DdlSearchContainerProps {
  ref: React.RefObject<HTMLDivElement>;
  tabIndex: number;
  'data-shortcut-scope': ShortcutScope;
  'data-ddl-search-open'?: 'true';
  onMouseDown: (e: React.MouseEvent) => void;
}

export interface DdlSearchController {
  containerProps: DdlSearchContainerProps;
  /** Render above the SQL viewport. Returns null while closed. */
  renderAddons: () => React.ReactNode;
  searchOpen: boolean;
  searchSupported: boolean;
}

/**
 * Scoped search over the rendered DDL (Issue #2748, plan B1).
 *
 * - Highlighting: CSS Custom Highlight API only; the Shiki DOM is never mutated.
 * - Locating: Range.getClientRects() -> first visible rect -> center inside the
 *   nearest scrollable ancestor (no parentElement.scrollIntoView guesses).
 * - Shortcut: the container owns `data-shortcut-scope="viewDdl"` and a
 *   bubble-phase keydown listener that honours the effective (user-overridable)
 *   DdlSearch binding, including `disabled`.
 */
export function useDdlSearch(options: UseDdlSearchOptions): DdlSearchController {
  const { sql, resetKey } = options;

  const containerRef = useRef<HTMLDivElement>(null);
  const searchBarRef = useRef<DdlSearchBarRef>(null);
  const instanceIdRef = useRef<string>('');
  if (!instanceIdRef.current) {
    instanceIdRef.current = nextDdlSearchInstanceId();
  }
  const instanceId = instanceIdRef.current;

  const [searchOpen, setSearchOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(-1);
  const [matchCount, setMatchCount] = useState(0);
  const [searchSupported] = useState(() => isDdlSearchHighlightSupported());

  const matchesRef = useRef<DdlSearchMatch[]>([]);
  const rangesRef = useRef<Range[]>([]);
  const warnOnceRef = useRef(false);
  const rafRef = useRef(0);

  // Latest values for DOM callbacks (observer / keydown) without re-binding.
  const stateRef = useRef({ sql, query, searchOpen, activeIndex });
  stateRef.current = { sql, query, searchOpen, activeIndex };

  const shortcutOverrides = useGlobalStore((s) => s.shortcutOverrides);
  const searchShortcut = useMemo(
    () => getEffectiveShortcutConfig(ShortcutAction.DdlSearch, shortcutOverrides as ShortcutOverrides),
    [shortcutOverrides],
  );
  const searchShortcutRef = useRef(searchShortcut);
  searchShortcutRef.current = searchShortcut;

  const getHighlightRegistry = (): Map<string, Highlight> | null => {
    if (!searchSupported) {
      return null;
    }
    return (CSS as unknown as { highlights: Map<string, Highlight> }).highlights;
  };

  const clearHighlights = useCallback(() => {
    const registry = getHighlightRegistry();
    if (!registry) {
      return;
    }
    const names = getDdlSearchHighlightNames(instanceId);
    registry.delete(names.all);
    registry.delete(names.active);
    rangesRef.current = [];
    matchesRef.current = [];
  }, [instanceId, searchSupported]);

  const findContentRoot = useCallback((): HTMLElement | null => {
    const container = containerRef.current;
    if (!container) {
      return null;
    }
    // The Shiki <pre> carries a stable "shiki" class; the highlighter's error
    // fallback renders a plain <pre>. Either way textContent === source SQL.
    const preview = container.querySelector('[data-sql-preview="canonical"]');
    return (preview?.querySelector('pre.shiki') ?? preview?.querySelector('pre') ?? null) as HTMLElement | null;
  }, []);

  const scrollRangeIntoCenter = useCallback((range: Range) => {
    if (typeof range.getClientRects !== 'function') {
      // Non-layout engines (e.g. jsdom in tests) have no box tree.
      return;
    }
    const rect = pickVisibleRect(range.getClientRects());
    if (!rect) {
      return;
    }
    const scrollContainer = findScrollContainer(range.startContainer);
    if (!scrollContainer) {
      return;
    }
    const containerRect = scrollContainer.getBoundingClientRect();
    if (isRectFullyVisible(rect, containerRect)) {
      return;
    }
    scrollContainer.scrollTop += computeCenteredScrollDelta(rect, containerRect);
  }, []);

  const applyActiveHighlight = useCallback(
    (nextIndex: number, shouldScroll: boolean) => {
      const registry = getHighlightRegistry();
      if (!registry) {
        return;
      }
      const names = getDdlSearchHighlightNames(instanceId);
      const ranges = rangesRef.current;
      if (nextIndex >= 0 && nextIndex < ranges.length) {
        registry.set(names.active, new Highlight(ranges[nextIndex]));
        if (shouldScroll) {
          scrollRangeIntoCenter(ranges[nextIndex]);
        }
      } else {
        registry.delete(names.active);
      }
    },
    [instanceId, searchSupported],
  );

  const recompute = useCallback(
    (recomputeOptions?: { jumpToFirst?: boolean }) => {
      const registry = getHighlightRegistry();
      const { sql: currentSql, query: currentQuery, searchOpen: open } = stateRef.current;
      if (!registry || !open) {
        return;
      }
      const names = getDdlSearchHighlightNames(instanceId);
      const matches = findDdlSearchMatches(currentSql, currentQuery);
      matchesRef.current = matches;
      if (!matches.length) {
        registry.delete(names.all);
        registry.delete(names.active);
        rangesRef.current = [];
        setMatchCount(0);
        setActiveIndex(-1);
        return;
      }
      const contentRoot = findContentRoot();
      const ranges = contentRoot ? createMatchRanges(contentRoot, currentSql, matches) : null;
      if (!ranges) {
        // Rendered text is not in sync with the SQL yet (async Shiki paint);
        // the MutationObserver re-triggers recompute once the DOM settles.
        return;
      }
      rangesRef.current = ranges;
      registry.set(names.all, new Highlight(...ranges));
      setMatchCount(ranges.length);
      const previousActive = stateRef.current.activeIndex;
      const shouldJump = recomputeOptions?.jumpToFirst || previousActive < 0;
      const nextIndex = shouldJump ? 0 : Math.min(previousActive, ranges.length - 1);
      setActiveIndex(nextIndex);
      applyActiveHighlight(nextIndex, true);
    },
    [applyActiveHighlight, findContentRoot, instanceId, searchSupported],
  );

  const scheduleRecompute = useCallback(
    (recomputeOptions?: { jumpToFirst?: boolean }) => {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = requestAnimationFrame(() => recompute(recomputeOptions));
    },
    [recompute],
  );

  const openSearch = useCallback(() => {
    if (!searchSupported) {
      if (!warnOnceRef.current) {
        warnOnceRef.current = true;
        // Compatibility limitation: no CSS Custom Highlight API (old JCEF/Chromium).
        console.warn('[DdlSearch] CSS Custom Highlight API is unavailable; scoped DDL search is disabled.');
      }
      return;
    }
    setSearchOpen(true);
  }, [searchSupported]);

  const resetSearch = useCallback(() => {
    cancelAnimationFrame(rafRef.current);
    setSearchOpen(false);
    setQuery('');
    setMatchCount(0);
    setActiveIndex(-1);
    clearHighlights();
  }, [clearHighlights]);

  const closeSearch = useCallback(() => {
    resetSearch();
    containerRef.current?.focus({ preventScroll: true });
  }, [resetSearch]);

  // Instance-scoped ::highlight() stylesheet, removed on close/unmount.
  useEffect(() => {
    if (!searchOpen || !searchSupported) {
      return;
    }
    const styleElement = document.createElement('style');
    styleElement.setAttribute(DDL_SEARCH_STYLE_ATTRIBUTE, instanceId);
    styleElement.textContent = buildDdlSearchHighlightStyleText(instanceId);
    document.head.appendChild(styleElement);
    return () => {
      styleElement.remove();
    };
  }, [searchOpen, searchSupported, instanceId]);

  // Focus the input once the bar is rendered, then run the initial search.
  useEffect(() => {
    if (!searchOpen) {
      return;
    }
    searchBarRef.current?.focus();
    scheduleRecompute({ jumpToFirst: true });
  }, [searchOpen, scheduleRecompute]);

  // Live search on query / SQL changes.
  useEffect(() => {
    if (!searchOpen) {
      return;
    }
    scheduleRecompute({ jumpToFirst: true });
  }, [query, sql, searchOpen, scheduleRecompute]);

  // Reset when the inspected DDL object changes.
  const resetKeyRef = useRef(resetKey);
  useEffect(() => {
    if (resetKeyRef.current === resetKey) {
      return;
    }
    resetKeyRef.current = resetKey;
    resetSearch();
  }, [resetKey, resetSearch]);

  // Re-map offsets whenever the highlighted DOM changes (async Shiki paint,
  // fold toggles). Mutations from the search bar itself are ignored.
  useEffect(() => {
    const container = containerRef.current;
    if (!container || !searchOpen || !searchSupported) {
      return;
    }
    const observer = new MutationObserver((mutations) => {
      const fromSearchBar = mutations.every(
        (mutation) =>
          mutation.target instanceof HTMLElement && mutation.target.closest('[data-ddl-search-bar]'),
      );
      if (!fromSearchBar) {
        scheduleRecompute();
      }
    });
    observer.observe(container, { childList: true, characterData: true, subtree: true });
    return () => observer.disconnect();
  }, [searchOpen, searchSupported, scheduleRecompute]);

  // Scoped shortcut handling (bubble phase on the container). The global
  // capture dispatcher skips events whose target sits inside this scope, so
  // the effective user binding - not a hardcoded Cmd/Ctrl+F - decides here.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (stateRef.current.searchOpen) {
          e.preventDefault();
          e.stopPropagation();
          closeSearch();
        }
        return;
      }
      const config = searchShortcutRef.current;
      if (config.disabled || !isShortcutEventMatch(e, config.binding)) {
        return;
      }
      e.preventDefault();
      e.stopPropagation();
      openSearch();
      if (stateRef.current.searchOpen) {
        searchBarRef.current?.focus();
      }
    };
    container.addEventListener('keydown', handleKeyDown);
    return () => {
      container.removeEventListener('keydown', handleKeyDown);
    };
  }, [closeSearch, openSearch]);

  // Final cleanup on unmount.
  useEffect(() => {
    return () => {
      cancelAnimationFrame(rafRef.current);
      clearHighlights();
    };
  }, [clearHighlights]);

  const jumpTo = useCallback(
    (direction: 1 | -1) => {
      const count = matchesRef.current.length;
      const nextIndex = nextActiveIndex(stateRef.current.activeIndex, count, direction);
      if (nextIndex < 0) {
        return;
      }
      setActiveIndex(nextIndex);
      applyActiveHighlight(nextIndex, true);
    },
    [applyActiveHighlight],
  );

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    const target = e.target as HTMLElement | null;
    if (target?.closest('input, textarea, button, a, [contenteditable="true"], [data-ddl-search-bar]')) {
      return;
    }
    const container = containerRef.current;
    if (!container) {
      return;
    }
    const activeElement = container.ownerDocument.activeElement;
    if (activeElement !== container && !container.contains(activeElement)) {
      // Clicking DDL text must move focus into the scope so the scoped
      // shortcut works; default selection/copy behaviour is left untouched.
      container.focus({ preventScroll: true });
    }
  }, []);

  const renderAddons = useCallback((): React.ReactNode => {
    if (!searchOpen) {
      return null;
    }
    return React.createElement(DdlSearchBar, {
      ref: searchBarRef,
      value: query,
      activeIndex,
      matchCount,
      onChange: setQuery,
      onNext: () => jumpTo(1),
      onPrev: () => jumpTo(-1),
      onClose: closeSearch,
    });
  }, [searchOpen, query, activeIndex, matchCount, jumpTo, closeSearch]);

  return {
    containerProps: {
      ref: containerRef,
      tabIndex: 0,
      'data-shortcut-scope': ShortcutScope.ViewDdl,
      ...(searchOpen ? { 'data-ddl-search-open': 'true' as const } : {}),
      onMouseDown: handleMouseDown,
    },
    renderAddons,
    searchOpen,
    searchSupported,
  };
}
