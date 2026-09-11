import React, { forwardRef, memo, useImperativeHandle, useRef } from 'react';
import { ChevronDown, ChevronUp, X } from 'lucide-react';
import { IconButton } from '@chat2db/ui';
import i18n from '@/i18n';
import SearchBar, { type SearchBarRef } from '@/components/SearchBar';
import { useStyles } from './style';

export interface DdlSearchBarProps {
  value: string;
  /** -1 when there is no active match. */
  activeIndex: number;
  matchCount: number;
  onChange: (value: string) => void;
  onNext: () => void;
  onPrev: () => void;
  onClose: () => void;
}

export interface DdlSearchBarRef {
  focus: () => void;
}

/**
 * Scoped find bar for the DDL preview, visually modelled after FESearch.
 * Rendered above the SQL viewport so navigation remains visible while scrolling.
 */
const DdlSearchBar = forwardRef<DdlSearchBarRef, DdlSearchBarProps>((props, ref) => {
  const { value, activeIndex, matchCount, onChange, onNext, onPrev, onClose } = props;
  const { styles, cx } = useStyles();
  const searchBarRef = useRef<SearchBarRef>(null);

  useImperativeHandle(ref, () => ({
    focus: () => {
      searchBarRef.current?.focus?.();
    },
  }));

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    // Cmd/Ctrl+A, copy, paste etc. keep their default input behaviour.
    if (e.key === 'Escape') {
      e.preventDefault();
      // Keep the Escape from bubbling into the surrounding modal / container.
      e.stopPropagation();
      onClose();
      return;
    }
    if (e.key === 'Enter' && e.shiftKey) {
      e.preventDefault();
      onPrev();
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      onNext();
    }
  };

  const searched = value.length > 0;

  return (
    <div className={styles.container} data-ddl-search-bar="true">
      <SearchBar
        ref={searchBarRef}
        className={styles.searchBar}
        placeholder={i18n('common.text.searchPlaceholder')}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={handleKeyDown}
      />
      {searched &&
        (matchCount > 0 ? (
          <div className={styles.count}>{i18n('workspace.searchResult.count', activeIndex + 1, matchCount)}</div>
        ) : (
          <div className={cx(styles.noSearchResult, styles.count)}>{i18n('common.text.noSearchResult')}</div>
        ))}
      <div className={styles.buttonGroup}>
        <IconButton
          size={{ boxSize: 20, iconSize: 18, borderRadius: 3 }}
          icon={ChevronUp}
          title={i18n('workspace.searchResult.prev')}
          onClick={onPrev}
        />
        <IconButton
          size={{ boxSize: 20, iconSize: 18, borderRadius: 3 }}
          icon={ChevronDown}
          title={i18n('workspace.searchResult.next')}
          onClick={onNext}
        />
        <IconButton
          size={{ boxSize: 20, iconSize: 18, borderRadius: 3 }}
          icon={X}
          title={i18n('workspace.searchResult.close')}
          onClick={onClose}
        />
      </div>
    </div>
  );
});

export default memo(DdlSearchBar);
