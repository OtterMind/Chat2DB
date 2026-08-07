import * as VTable from '@visactor/vtable';
import { getResultFieldAtTableColumn } from '../../columnState';

const handleSetNull = (tableInstance: VTable.ListTable, readOnlyFields: ReadonlySet<string> = new Set()) => {
  const cells = tableInstance.getSelectedCellInfos() || [];
  cells.forEach((rowCells) => {
    rowCells.forEach((cell) => {
      // does not change the meter header
      const field = getResultFieldAtTableColumn(tableInstance, cell.col, cell.row);
      if (cell.row === 0 || (field && readOnlyFields.has(field))) {
        return;
      }
      tableInstance.changeCellValue(cell.col, cell.row, null);
    });
  });
};

export default handleSetNull;
