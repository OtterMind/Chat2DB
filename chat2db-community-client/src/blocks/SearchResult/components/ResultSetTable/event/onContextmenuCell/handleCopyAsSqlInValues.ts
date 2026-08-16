import { IHandleContextmenuProps } from '../../typings';

type CopyAsSqlInValuesErrorKey =
  | 'common.sqlInValues.emptySelection'
  | 'common.sqlInValues.singleColumnRequired';

export interface CopyAsSqlInValuesResult {
  operations?: any[];
  errorKey?: CopyAsSqlInValuesErrorKey;
}

const handleCopyAsSqlInValues = (props: Pick<IHandleContextmenuProps, 'tableInstance'>): CopyAsSqlInValuesResult => {
  const { tableInstance } = props;
  const selectedCells = tableInstance.getSelectedCellInfos() || [];
  if (!selectedCells.length) {
    return { errorKey: 'common.sqlInValues.emptySelection' };
  }

  let selectedField: string | undefined;
  const operations: any[] = [];

  for (const rowCells of selectedCells) {
    const dataCells = (rowCells || []).filter((cell: any) => cell.row > 0 && cell.col > 0);
    if (dataCells.length !== 1) {
      return { errorKey: 'common.sqlInValues.singleColumnRequired' };
    }

    const cell = dataCells[0];
    const field = String(cell.field);
    if (selectedField === undefined) {
      selectedField = field;
    } else if (selectedField !== field) {
      return { errorKey: 'common.sqlInValues.singleColumnRequired' };
    }

    const originData = cell.originData;
    operations.push({
      type: 'IN_VALUES',
      rowId: originData?.CHAT2DB_ROW_NUMBER,
      dataList: originData,
      selectCols: [field],
      selectedCell: originData?.__CHAT2DB_CELL_META__?.[Number(field)],
    });
  }

  if (!operations.length) {
    return { errorKey: 'common.sqlInValues.emptySelection' };
  }
  return { operations };
};

export default handleCopyAsSqlInValues;
