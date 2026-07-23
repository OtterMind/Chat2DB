import { CSSProperties, memo, useMemo } from 'react';
import { CodeHighlighter, type CodeHighlighterProps } from '@chat2db/ui';
import { useStyles } from './style';

type HighlighterProps = Pick<CodeHighlighterProps, 'type' | 'copyable' | 'foldable' | 'wrap' | 'renderAddons'>;

export interface SQLPreviewProps extends HighlighterProps {
  className?: string;
  style?: CSSProperties;
  sql?: string | null;
  language?: string;
  source?: string;
  maxHeight?: number | string;
  surface?: 'default' | 'transparent';
}

function resolveSize(size?: number | string) {
  if (typeof size === 'number') {
    return `${size}px`;
  }
  return size;
}

const SQLPreview = memo<SQLPreviewProps>((props) => {
  const {
    className,
    style,
    sql,
    language = 'sql',
    source,
    maxHeight,
    surface = 'default',
    type = 'pure',
    copyable = true,
    foldable = false,
    wrap = true,
    renderAddons,
  } = props;
  const { styles, cx } = useStyles({ wrap });

  const wrapperStyle = useMemo<CSSProperties>(
    () => ({
      maxHeight: resolveSize(maxHeight),
      ...style,
    }),
    [maxHeight, style],
  );

  return (
    <div
      className={cx(styles.sqlPreview, className)}
      style={wrapperStyle}
      data-sql-preview="canonical"
      data-sql-preview-source={source}
      data-sql-preview-wrap={wrap ? 'wrap' : 'scroll'}
    >
      <CodeHighlighter
        className={cx(styles.highlighter, surface === 'transparent' && styles.transparentHighlighter)}
        code={sql || ''}
        language={language}
        type={type}
        copyable={copyable}
        foldable={foldable}
        wrap={wrap}
        renderAddons={renderAddons}
      />
    </div>
  );
});

export default SQLPreview;
