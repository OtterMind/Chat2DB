import * as VTable from '@visactor/vtable';
import { ISelectEvent } from '../../typings';
import { getResultCellMetaAtTableColumn, getResultFieldAtTableColumn } from '../../columnState';

// View and modify current data
const handleViewUpdateData = (tableInstance: VTable.ListTable, selectEvent: ISelectEvent) => {
  const cells = selectEvent;
  const { col, row } = cells;
  const record = tableInstance.getRecordByCell(col, row);
  const cellMeta = getResultCellMetaAtTableColumn(tableInstance, record, col, row);
  const field = getResultFieldAtTableColumn(tableInstance, col, row);
  return { col, row, tableInstance, cellMeta, field };
};

export default handleViewUpdateData;
