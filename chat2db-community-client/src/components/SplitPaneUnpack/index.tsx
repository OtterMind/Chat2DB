import React, { memo, useRef, useEffect, useState } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { useStyles } from './style';

interface IProps {
  className?: string;
  children: React.ReactNode;
  onUnfold?: () => void;
  onPackUp?: () => void;
  direction?: 'vertical' | 'horizontal';
}

export default memo<IProps>((props) => {
  const { className, children, onUnfold, onPackUp, direction = 'vertical' } = props;
  const { styles, cx } = useStyles(direction);
  const splitPaneUnpackRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState(0);

  useEffect(() => {
    const resizeObserver = new ResizeObserver(() => {
      const currentSize =
        direction === 'vertical' ? splitPaneUnpackRef.current?.clientHeight : splitPaneUnpackRef.current?.clientWidth;
      setSize(currentSize || 0);
    });
    resizeObserver.observe(splitPaneUnpackRef.current as Element);
    return () => {
      resizeObserver.disconnect();
    };
  }, [direction]);

  const DirectionIcon = size === 0 ? ChevronUp : ChevronDown;

  const handleClick = () => {
    if (size === 0) {
      onUnfold?.();
    } else {
      onPackUp?.();
    }
  };

  return (
    <div className={cx(styles.splitPaneUnpack, className)} ref={splitPaneUnpackRef}>
      <div className="operatingHandleBox">
        <div className="operatingHandle" onClick={handleClick}>
          <DirectionIcon className="operatingHandleIcon" size={14} />
        </div>
      </div>
      {children}
    </div>
  );
});
