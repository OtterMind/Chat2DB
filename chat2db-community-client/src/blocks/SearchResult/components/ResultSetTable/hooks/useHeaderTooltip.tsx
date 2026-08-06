import { useEffect, useRef } from 'react';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import ContextMenu, { ContextMenuRef } from '@/components/ContextMenu';
import { useStyles } from '../style';
import i18n from '@/i18n';
import { getHeaderMetadataRows, type HeaderMetadataKey } from '../headerMetadata';

const useHeaderTooltip = ({ tableInstance }: { tableInstance: ITableInstance | null }) => {
  const contextMenuRef = useRef<ContextMenuRef>(null);
  const { styles } = useStyles();

  const renderHeaderTooltip = (originalData) => {
    const labels: Record<HeaderMetadataKey, string> = {
      fieldName: i18n('workspace.resultSet.fieldName'),
      fieldType: i18n('workspace.resultSet.fieldType'),
      fieldComment: i18n('workspace.resultSet.fieldComment'),
    };
    return (
      <div className={styles.headerTooltip}>
        {getHeaderMetadataRows(originalData).map((row) => (
          <div className={styles.headerTooltipRow} key={row.key}>
            <div className={styles.headerTooltipLabel}>{labels[row.key]}</div>
            <div className={styles.headerTooltipValue}>{row.value}</div>
          </div>
        ))}
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
