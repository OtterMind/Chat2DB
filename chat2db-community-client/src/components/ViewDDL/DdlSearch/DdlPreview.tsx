import { memo } from 'react';
import SQLPreview from '@/components/SQLPreview';
import { useDdlSearch } from './useDdlSearch';
import { useStyles } from './style';

interface DdlPreviewProps {
  sql: string;
  resetKey?: unknown;
  source: string;
  className?: string;
}

/** Keep search controls outside the scrolling, read-only SQL preview. */
export default memo<DdlPreviewProps>(({ sql, resetKey, source, className }) => {
  const { styles, cx } = useStyles();
  const { containerProps, renderAddons } = useDdlSearch({ sql, resetKey });
  return (
    <div className={cx(styles.preview, className)} {...containerProps}>
      {renderAddons()}
      {/* Remount the async highlighter when content changes to discard old paints. */}
      <SQLPreview key={sql} className={styles.viewport} sql={sql} source={source} foldable />
    </div>
  );
});
