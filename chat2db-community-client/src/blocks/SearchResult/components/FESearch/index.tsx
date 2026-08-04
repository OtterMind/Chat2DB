import React, {
  memo,
  forwardRef,
  useImperativeHandle,
  ForwardedRef,
  useEffect,
  useRef,
  useState,
  useCallback,
} from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import i18n from '@/i18n';
import { useStyles } from './style';
import { SearchComponent } from '@visactor/vtable-search';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import { hexToRgba } from '@/utils/color';
import { debounce } from 'lodash';
import { IconButton, SearchBar } from '@chat2db/ui';

interface IProps {
  className?: string;
  tableInstance: ITableInstance | null;
  // closed callback
  onClose?: () => void;
  searchAreaId: string;
}

export interface FESearchRef {
  close: () => void;
  focus: () => void;
}

const FESearch = forwardRef((props: IProps, ref: ForwardedRef<FESearchRef>) => {
  const { className, tableInstance, searchAreaId, onClose } = props;
  const { styles, cx } = useStyles();
  const searchRef = useRef<SearchComponent | null>(null);
  const lastSearchValueRef = useRef('');
  // The value of the last search
  const [searchResult, setSearchResult] = useState<any>(null);
  const [value, setValue] = useState('');
  const [lastSearchValue, setLastSearchValue] = useState('');
  const feSearchRef = useRef<HTMLDivElement>(null);
  const searchBarRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!tableInstance) return;
    const highlightCellStyleBgColor = hexToRgba('#ff0', 20);
    const focuseHighlightCellStyleBgColor = hexToRgba('#ff0', 60);

    const search = new SearchComponent({
      table: tableInstance,
      autoJump: true,
      highlightCellStyle: {
        bgColor: highlightCellStyleBgColor,
      } as any,
      focuseHighlightCellStyle: {
        bgColor: focuseHighlightCellStyleBgColor,
      } as any,
    });
    searchRef.current = search;

    if (lastSearchValueRef.current) {
      const res = search.search(lastSearchValueRef.current);
      setSearchResult({
        index: res.index,
        count: res.results.length,
      });
    }

    return () => {
      if (searchRef.current === search) {
        searchRef.current = null;
      }
    };
  }, [tableInstance]);

  useImperativeHandle(ref, () => ({
    close: handleClose,
    focus: () => {
      searchBarRef.current?.focus?.();
    },
  }));

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    // If you press esc, close
    if (e.key === 'Escape') {
      onClose?.();
      return;
    }
    // If it is shift+enter, the previous
    if (e.key === 'Enter' && e.shiftKey) {
      handleJumpPrev();
      return;
    }
    if (e.key === 'Enter') {
      // Press enter, if there is no value, clear the search
      if (!value) {
        handleClearSearch();
        return;
      }

      // If there isvalue === lastSearchValue Description
      if (value === lastSearchValue) {
        handleJumpNext();
        return;
      }

      // If there is value, search
      handleSearch();
    }
  };

  const handleSearch = (_value?: string) => {
    if (!value && !_value) {
      handleClearSearch();
      return;
    }
    const res = searchRef.current?.search(_value || value);
    setSearchResult({
      index: res?.index,
      count: res?.results.length,
    });
    const nextSearchValue = _value || value;
    lastSearchValueRef.current = nextSearchValue;
    setLastSearchValue(nextSearchValue);
  };

  const handleClearSearch = () => {
    searchRef.current?.clear();
    lastSearchValueRef.current = '';
    setLastSearchValue('');
    setSearchResult(null);
  };

  const handleJumpNext = () => {
    if (!searchResult) return;
    const res = searchRef.current?.next();
    setSearchResult({
      index: res?.index,
      count: res?.results.length,
    });
  };

  const handleJumpPrev = () => {
    if (!searchResult) return;
    const res = searchRef.current?.prev();
    setSearchResult({
      index: res?.index,
      count: res?.results.length,
    });
  };

  const handleClose = () => {
    handleClearSearch();
    onClose?.();
  };

  // Search for anti-shake
  const debouncedSearch = useCallback(
    debounce((_value) => {
      handleSearch(_value);
    }, 500),
    [],
  );

  // Search box value changes
  const handleChange = (e) => {
    setValue(e.target.value);
    debouncedSearch(e.target.value);
  };

  return (
    <div className={cx(className, styles.container)} ref={feSearchRef}>
      <SearchBar
        ref={searchBarRef}
        className={styles.resultSetSearchBar}
        placeholder={i18n('workspace.tips.searchResultData')}
        value={value}
        searchAreaId={searchAreaId}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
      />
      {searchResult && (
        <>
          {searchResult.count > 0 ? (
            <div className={styles.count}>
              {i18n('workspace.searchResult.count', searchResult.index + 1, searchResult.count)}
            </div>
          ) : (
            <div className={cx(styles.noSearchResult, styles.count)}>{i18n('common.text.noSearchResult')}</div>
          )}
        </>
      )}
      <div className={styles.buttonGroup}>
        <IconButton
          size={{
            boxSize: 20,
            iconSize: 18,
            borderRadius: 3,
          }}
          icon={ChevronUp}
          title={i18n('workspace.searchResult.prev')}
          onClick={handleJumpPrev}
        />
        <IconButton
          size={{
            boxSize: 20,
            iconSize: 18,
            borderRadius: 3,
          }}
          icon={ChevronDown}
          title={i18n('workspace.searchResult.next')}
          onClick={handleJumpNext}
        />
        <IconButton
          size={{
            boxSize: 20,
            iconSize: 18,
            borderRadius: 3,
          }}
          code="icon-close"
          title={i18n('workspace.searchResult.close')}
          onClick={handleClose}
        />
      </div>
    </div>
  );
});

export default memo(FESearch);
