import { useEffect, useRef } from 'react';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import ContextMenu, { ContextMenuRef } from '@/components/ContextMenu';
import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    tooltipContainer: css`
      border-radius: 4px;
      background-color: ${token.colorBgBase};
      box-shadow: ${token.boxShadow};
      border: 1px solid ${token.colorBorderSecondary};
      padding: 6px;
      max-height: 200px;
      overflow: auto;
      word-break: break-all;
      box-sizing: border-box;
    `,
    copyButton: css`
      display: inline-block;
      text-align: center;
      margin-right: 6px;
      transform: translateY(2px);
    `,
  };
});

const useTooltip = ({ tableInstance, tooltip }: { tableInstance: ITableInstance | null; tooltip?: boolean }) => {
  const contextMenuRef = useRef<ContextMenuRef>(null);
  const { styles } = useStyles();

  const renderTooltip = (col, row) => {
    const data = tableInstance?.getCellValue(col, row);
    return <div className={styles.tooltipContainer}>{data}</div>;
  };

  useEffect(() => {
    if (!tableInstance || !tooltip) return;
    let mouseenterTimeout: any = null;

    const mouseenter_cell_id = tableInstance?.on('mouseenter_cell', (args) => {
      const { col, row, cellRange } = args;

      const isHeader = tableInstance.isHeader(col, row);

      if (isHeader) {
        return;
      }

      mouseenterTimeout = setTimeout(() => {
        // Read the current pointer position.
        contextMenuRef?.current?.openDropdown({
          position: 'absolute',
          event: {
            clientX: cellRange?.bounds.x1,
            clientY: cellRange?.bounds.y2,
          },
          dropdownRender: renderTooltip(col, row),
        });
      }, 800);
    });

    const clearMouseenterTimeout = () => {
      if (mouseenterTimeout) {
        clearTimeout(mouseenterTimeout);
      }
    };

    const clearMouseenter = () => {
      clearMouseenterTimeout();
      contextMenuRef?.current?.closeDropdown();
    };

    const mouseleave_cell_id = tableInstance?.on('mouseleave_cell', () => {
      clearMouseenter();
    });

    const mouseleave_table = tableInstance?.on('mouseleave_table', () => {
      clearMouseenter();
    });

    return () => {
      clearMouseenter();
      tableInstance.off(mouseenter_cell_id);
      tableInstance.off(mouseleave_cell_id);
      tableInstance.off(mouseleave_table);
    };
  }, [tableInstance]);

  if (!tooltip) return null;

  return <ContextMenu ref={contextMenuRef} />;
};

export default useTooltip;
