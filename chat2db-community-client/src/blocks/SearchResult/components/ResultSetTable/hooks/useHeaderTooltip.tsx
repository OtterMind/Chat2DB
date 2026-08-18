import { useEffect, useRef } from 'react';
import { createStyles } from 'antd-style';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import ContextMenu, { ContextMenuRef } from '@/components/ContextMenu';
import type { ITableHeaderItem } from '@/typings/database';
import { getHeaderMetadataRows } from '../headerMetadata';
import { getResultColumnAtTableColumn } from '../columnState';

const HEADER_TOOLTIP_DELAY = 800;

const useStyles = createStyles(({ css, token }) => ({
  tooltip: css`
    width: max-content;
    max-width: min(480px, calc(100vw - 32px));
    max-height: min(320px, calc(100vh - 32px));
    overflow: auto;
    padding: 8px 10px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 4px;
    background: ${token.colorBgElevated};
    box-shadow: ${token.boxShadowSecondary};
  `,
  line: css`
    max-width: 100%;
    line-height: 1.5;
    white-space: normal;
    overflow-wrap: anywhere;
  `,
  fieldName: css`
    color: ${token.colorText};
    font-weight: 600;
  `,
  fieldType: css`
    color: ${token.colorPrimary};
  `,
  fieldComment: css`
    color: ${token.colorTextSecondary};
  `,
}));

const HeaderTooltipContent = ({ originalData }: { originalData: ITableHeaderItem }) => {
  const { styles, cx } = useStyles();
  const rows = getHeaderMetadataRows(originalData);

  return (
    <div className={styles.tooltip}>
      {rows.map((item) => (
        <div className={cx(styles.line, styles[item.key])} key={item.key}>
          {item.value}
        </div>
      ))}
    </div>
  );
};

const useHeaderTooltip = ({ tableInstance }: { tableInstance: ITableInstance | null }) => {
  const contextMenuRef = useRef<ContextMenuRef>(null);

  useEffect(() => {
    if (!tableInstance) return;

    let mouseenterTimeout: ReturnType<typeof setTimeout> | undefined;
    const clearMouseenterTimeout = () => {
      if (mouseenterTimeout !== undefined) {
        clearTimeout(mouseenterTimeout);
        mouseenterTimeout = undefined;
      }
    };
    const closeTooltip = () => {
      clearMouseenterTimeout();
      contextMenuRef.current?.closeDropdown();
    };

    const mouseenterCellId = tableInstance.on('mouseenter_cell', ({ col, row, cellRange }) => {
      clearMouseenterTimeout();
      if (!tableInstance.isHeader(col, row)) {
        return;
      }

      const originalData = getResultColumnAtTableColumn(tableInstance, col, row)?.originalData;
      if (!originalData) {
        return;
      }

      mouseenterTimeout = setTimeout(() => {
        contextMenuRef.current?.openDropdown({
          position: 'absolute',
          event: {
            clientX: cellRange?.bounds.x1,
            clientY: cellRange?.bounds.y2,
          },
          dropdownRender: <HeaderTooltipContent originalData={originalData} />,
        });
      }, HEADER_TOOLTIP_DELAY);
    });
    const mouseleaveCellId = tableInstance.on('mouseleave_cell', closeTooltip);
    const mouseleaveTableId = tableInstance.on('mouseleave_table', closeTooltip);

    return () => {
      clearMouseenterTimeout();
      tableInstance.off(mouseenterCellId);
      tableInstance.off(mouseleaveCellId);
      tableInstance.off(mouseleaveTableId);
    };
  }, [tableInstance]);

  return <ContextMenu ref={contextMenuRef} />;
};

export default useHeaderTooltip;
