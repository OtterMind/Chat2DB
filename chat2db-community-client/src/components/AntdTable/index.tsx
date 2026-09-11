import { memo, useEffect, useRef, useState, type CSSProperties } from 'react';
import { useStyles } from './style';
import { Table, type TableProps } from 'antd';

interface IProps extends TableProps {
  className?: string;
  subHeight?: number;
  fillScrollBody?: boolean;
}

export default memo<IProps>((props) => {
  const { className, subHeight, fillScrollBody = false, ...tableProps } = props;
  const { styles, cx } = useStyles();
  const tableBoxRef = useRef<any>(null);
  const [tableScrollY, setTableScrollY] = useState(0);

  // Track tableBoxRef height changes and update tableScrollY when resized.
  useEffect(() => {
    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { height } = entry.contentRect;
        let fHeight = height - (subHeight || 55);
        if (tableProps.pagination) {
          fHeight = fHeight - 50;
        }
        setTableScrollY(fHeight);
      }
    });

    if (tableBoxRef.current) {
      resizeObserver.observe(tableBoxRef.current);
    }

    return () => {
      resizeObserver.disconnect();
    };
  }, []);

  return (
    <div
      className={cx(className, styles.tableBox, fillScrollBody && styles.fillScrollBody)}
      ref={tableBoxRef}
      style={{ '--chat2db-table-scroll-y': `${Math.max(tableScrollY, 0)}px` } as CSSProperties}
    >
      <Table
        style={{
          height: '100%',
        }}
        sticky
        pagination={false}
        {...tableProps}
        scroll={{ ...tableProps.scroll, y: tableScrollY }}
      />
    </div>
  );
});
