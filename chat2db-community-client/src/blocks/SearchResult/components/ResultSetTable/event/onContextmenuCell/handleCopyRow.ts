import { copyToClipboard } from '@/utils';
import { IHandleContextmenuProps } from '../../typings';
import { ContextmenuType } from '../../constants';
import { getRowOriginDataAsArray } from '@/blocks/CanvasTable/utils';
// import executeSql from '@/service/executeSql';

// Copy behavior insert statement/copy behavior update statement
const handleCopyRow = (props: IHandleContextmenuProps) => {
  const { tableInstance, type } = props;
  if (!tableInstance) return;
  const visibleColumns = tableInstance.columns.filter((column: any) => column.hide !== true);

  // Gets the selected actual data details collection
  const rowsDetails: any = [];
  const selectColsList: any = [];

  tableInstance.getSelectedCellInfos()?.map((cell, index) => {
    selectColsList[index] = [];
    cell.forEach((c) => {
      selectColsList[index].push(c.field);
    });
    if (!cell?.[0]?.row) return;
    rowsDetails.push(cell[0].originData);
  }) || [];

  // tab separated value
  if (type === ContextmenuType.tabSplit) {
    const list = getRowOriginDataAsArray(tableInstance, rowsDetails);
    copyToClipboard(list);
    return;
  }

  // tab-delimited field
  if (type === ContextmenuType.tabSplitField) {
    const fields: any = visibleColumns.map((_col: any) => _col.originalData?.name || _col.title || '');
    copyToClipboard(fields);
    return;
  }

  // Tab-separated value field
  if (type === ContextmenuType.tabSplitFieldAndValue) {
    const fields: any = visibleColumns.map((_col: any) => {
      return _col.originalData?.name || _col.title || '';
    });
    const list = getRowOriginDataAsArray(tableInstance, rowsDetails);
    copyToClipboard([fields, ...list]);
    return;
  }

  // Copy behavior insert/update/where statement
  if ([ContextmenuType.copyRowInsert, ContextmenuType.copyRowUpdate, ContextmenuType.copyRowWhere].includes(type)) {
    const getType = () => {
      switch (type) {
        case ContextmenuType.copyRowInsert:
          return 'CREATE';
        case ContextmenuType.copyRowUpdate:
          return 'UPDATE_COPY';
        case ContextmenuType.copyRowWhere:
          return 'WHERE';
        default:
          return 'CREATE';
      }
    };

    const operations = rowsDetails.map((cell: any, index) => {
      return {
        type: getType(),
        rowId: cell.CHAT2DB_ROW_NUMBER,
        dataList: cell,
        selectCols: selectColsList[index],
      };
    });
    return operations;
  }
};

export default handleCopyRow;
