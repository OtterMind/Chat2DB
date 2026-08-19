import { SearchBar as BaseSearchBar, type SearchBarProps } from '@chat2db/ui';
import { forwardRef, memo } from 'react';
import { useStyles } from './style';

export interface SearchBarRef {
  focus: () => void;
  blur: () => void;
}

const SearchBar = forwardRef<SearchBarRef, SearchBarProps>((props, ref) => {
  const { className, ...rest } = props;
  const { styles, cx } = useStyles();

  return <BaseSearchBar ref={ref} className={cx(styles.searchBar, className)} {...rest} />;
});

export default memo(SearchBar);
