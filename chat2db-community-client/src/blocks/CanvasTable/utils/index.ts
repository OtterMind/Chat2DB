// Determine whether a row/column coordinate exists in a two-dimensional array of coordinates.
import { ITableInstance } from '@/blocks/CanvasTable/typings';

export const isSelected = (
  selectEvent: {
    row: number;
    col: number;
    [key: string]: any;
  },
  selectedCells: {
    row: number;
    col: number;
    [key: string]: any;
  }[][],
) => {
  return selectedCells.some((row) => row.some((cell) => cell.row === selectEvent.row && cell.col === selectEvent.col));
};

// Get the corresponding value from the header field through the row details and convert it into an array.
export const getRowOriginDataAsArray = (
  tableInstance: ITableInstance,
  rowsDetails: {
    [key: string]: any;
  },
) => {
  const list: (string | null)[][] = [];
  const columns = tableInstance.columns.filter((column: any) => column.hide !== true);
  rowsDetails?.forEach((rowDetail, index) => {
    columns?.forEach((col) => {
      if (!list[index]) {
        list[index] = [];
      }
      list[index].push(rowDetail[col.field as string] ?? null);
    });
  });
  return list;
};

// Get the original data of the current row
export const getRowOriginData = (tableInstance: ITableInstance, rowId: string) => {
  const originData = tableInstance.records.find((record) => {
    return record.CHAT2DB_ROW_NUMBER === rowId;
  });

  return originData
};

// Find the row number of the current data through the data id
export const findRowNumberById = (tableInstance: ITableInstance, id: string) => {
  const records = getSortedOrFilteredData(tableInstance) || [];
  const index = records?.findIndex((row) => row?.CHAT2DB_ROW_NUMBER === id);
  return index + 1;
};

// Find the row number of the current data through the data id set
export const findRowNumbersByIds = (tableInstance: ITableInstance, ids: string[]) => {
  const records = getSortedOrFilteredData(tableInstance) || [];
  const idList = ids.map((id) => {
    const index = records.findIndex((row) => {
      return row?.CHAT2DB_ROW_NUMBER === id;
    });
    return index + 1;
  });
  return idList;
};

// Find the column number where the current column id is located
export const findColNumberById = (tableInstance: ITableInstance, field: string) => {
  return tableInstance.getTableIndexByField(field);
};

// Get sorted or filtered data
export const getSortedOrFilteredData = (tableInstance: ITableInstance) => {
  const initRecords = tableInstance.records;
  const currentIndexedData = tableInstance?.dataSource?.currentIndexedData;
  const records = currentIndexedData?.map((index) => initRecords[index as number]);
  return records || [];
};
