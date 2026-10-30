import { useRef, useState, type ReactNode } from 'react';
import { createStyles } from 'antd-style';
import { DEFAULT_SPLIT, SPLITTER_WIDTH, splitAtPointer, splitForKey } from './split';

interface Props {
  left: ReactNode;
  right: ReactNode;
  label: string;
}

const useStyles = createStyles(({ css, token }) => ({
  container: css`
    display: grid;
    flex: 1;
    min-width: 0;
    min-height: 0;
    overflow: hidden;
  `,
  resizing: css`
    cursor: col-resize;
    user-select: none;
  `,
  separator: css`
    position: relative;
    cursor: col-resize;
    touch-action: none;
    outline-offset: -2px;

    &::before, &::after {
      position: absolute;
      left: 50%;
      content: '';
      background: ${token.colorBorder};
    }

    &::before { top: 0; bottom: 0; width: 1px; }
    &::after { top: calc(50% - 18px); width: 3px; height: 36px; border-radius: 3px; }
    &:hover::after, &:focus-visible::after { background: ${token.colorPrimary}; }
  `,
}));

export default function ResizableColumns({ left, right, label }: Props) {
  const { styles, cx } = useStyles();
  const container = useRef<HTMLDivElement>(null);
  const [percent, setPercent] = useState(DEFAULT_SPLIT);
  const [resizing, setResizing] = useState(false);

  return (
    <div
      ref={container}
      className={cx(styles.container, resizing && styles.resizing)}
      style={{ gridTemplateColumns: `minmax(0, ${percent}fr) ${SPLITTER_WIDTH}px minmax(0, ${100 - percent}fr)` }}
    >
      {left}
      <div
        className={styles.separator}
        role="separator"
        aria-label={label}
        aria-orientation="vertical"
        aria-valuemin={25}
        aria-valuemax={75}
        aria-valuenow={Math.round(percent)}
        tabIndex={0}
        onPointerDown={(event) => {
          if (event.button !== 0) return;
          event.preventDefault();
          event.currentTarget.setPointerCapture(event.pointerId);
          setResizing(true);
        }}
        onPointerMove={(event) => {
          if (!event.currentTarget.hasPointerCapture(event.pointerId) || !container.current) return;
          const bounds = container.current.getBoundingClientRect();
          setPercent(splitAtPointer(event.clientX, bounds.left, bounds.width));
        }}
        onPointerUp={(event) => {
          if (event.currentTarget.hasPointerCapture(event.pointerId)) {
            event.currentTarget.releasePointerCapture(event.pointerId);
          }
        }}
        onLostPointerCapture={() => setResizing(false)}
        onKeyDown={(event) => {
          const next = splitForKey(percent, event.key);
          if (next !== undefined) {
            event.preventDefault();
            setPercent(next);
          }
        }}
        onDoubleClick={() => setPercent(50)}
      />
      {right}
    </div>
  );
}
