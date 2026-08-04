import * as VTable from '@visactor/vtable';

const onChangeCellValue = (tableInstance: VTable.ListTable, handleCellValueChange) => {
  // Reentry guard: when a large-value cell is edited, the listener programmatically
  // calls changeCellValue to restore the original display. VTable re-fires
  // change_cell_value synchronously inside that call (because oldValue !== changedValue),
  // which would re-enter this listener and oscillate forever. The flag suppresses the
  // synthetic re-fire; it is only set during our own programmatic restore, so real user
  // edits are unaffected.
  let isRestoring = false;
  const id = tableInstance.on('change_cell_value', (event) => {
    if (isRestoring || event.currentValue === event.changedValue) {
      return;
    }
    const { row, col } = event;
    const originData = tableInstance.getRecordByCell(col, row);
    const cellMeta = originData?.__CHAT2DB_CELL_META__?.[col];
    if (cellMeta?.largeValue) {
      isRestoring = true;
      try {
        tableInstance.changeCellValue(col, row, event.currentValue);
      } finally {
        isRestoring = false;
      }
      return;
    }
    const headerField = tableInstance.getHeaderField(col, row);

    const rowId = originData.CHAT2DB_ROW_NUMBER;
    handleCellValueChange({
      ...event,
      rowId,
      field: headerField,
    });
  });
  return id;
};

export default onChangeCellValue;
