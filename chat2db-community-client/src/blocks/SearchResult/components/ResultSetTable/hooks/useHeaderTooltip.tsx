import { useEffect, useRef } from 'react';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import ContextMenu, { ContextMenuRef } from '@/components/ContextMenu';
import { useStyles } from '../style';

const useHeaderTooltip = ({ tableInstance }: { tableInstance: ITableInstance | null }) => {
  const contextMenuRef = useRef<ContextMenuRef>(null);
  const { styles } = useStyles();

  const renderHeaderTooltip = (originalData) => {
    return (
      <div className={styles.headerTooltip}>
        <div className={styles.headerTooltipFirst}>
          <div className={styles.columnName}>{originalData.name}:</div>
          <div className={styles.columnType}>
            {originalData.columnType}
            {originalData.columnSize && <>({originalData.columnSize})</>}
          </div>
        </div>
        <div className={styles.columnComment}>{originalData.comment}</div>
      </div>
    );
  };

  useEffect(() => {
    if (!tableInstance) return;
    let mouseenterTimeout: any = null;

    const mouseenter_cell_id = tableInstance.on('mouseenter_cell', (args) => {
      const { col, row, cellRange } = args;

      const isHeader = tableInstance.isHeader(col, row);
      if (!isHeader) {
        return;
      }
      const curColumn = tableInstance?.columns?.[col - 1] || {};
      const { originalData } = curColumn as any;
      if (!originalData) {
        return;
      }
      mouseenterTimeout = setTimeout(() => {
        // Get the current position of the mouse
        contextMenuRef?.current?.openDropdown({
          position: 'absolute',
          event: {
            clientX: cellRange?.bounds.x1,
            clientY: cellRange?.bounds.y2,
          },
          dropdownRender: renderHeaderTooltip(originalData),
        });
      }, 1000);
    });

    const clearMouseenterTimeout = () => {
      if (mouseenterTimeout) {
        clearTimeout(mouseenterTimeout);
      }
    };

    const mouseleave_cell_id = tableInstance.on('mouseleave_cell', () => {
      clearMouseenterTimeout();
      contextMenuRef?.current?.closeDropdown();
    });
    const mouseleave_table = tableInstance.on('mouseleave_table', () => {
      clearMouseenterTimeout();
      contextMenuRef?.current?.closeDropdown();
    });

    return () => {
      clearMouseenterTimeout();
      tableInstance.off(mouseenter_cell_id);
      tableInstance.off(mouseleave_cell_id);
      tableInstance.off(mouseleave_table);
    };
  }, [tableInstance]);

  return <ContextMenu ref={contextMenuRef} />;
};

export default useHeaderTooltip;
