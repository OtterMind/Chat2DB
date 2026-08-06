// import { IManageResultData } from '@/typings/database';
import * as VTable from '@visactor/vtable';
import { ContextmenuType } from '../constants';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import { OperationRecordUtils } from '../hooks/useOperationRecord';
import { IManageResultData, IResultCell } from '@/typings';

// selected cell
export type ISelectEvent = VTable.TYPES.MousePointerMultiCellEvent;

// Register the parameters required for the right-click menu
export interface IOnContextmenuEvent {
  resultData: IManageResultData;
  tableInstance: ITableInstance;
  operationRecordUtils: OperationRecordUtils;
  onTableOperationUtils: ITableOperationUtils;
  frozenColumnFields: readonly string[];
  onHideColumns: (fields: string[]) => void;
  onShowAllColumns: () => void;
  onFreezeColumns: (fields: string[]) => void;
  onUnfreezeAllColumns: () => void;
}

// The type of seat that needs to be processed when copying row data
export type ICopyRowType =
  | ContextmenuType.copyRowInsert
  | ContextmenuType.copyRowUpdate
  | ContextmenuType.copyRowWhere
  | ContextmenuType.tabSplit
  | ContextmenuType.tabSplitField
  | ContextmenuType.tabSplitFieldAndValue;

// Parameters required to handle right-click events
export interface IHandleContextmenuProps {
  tableInstance: ITableInstance;
  selectEvent: ISelectEvent;
  type: ICopyRowType;
}

// cell
export interface ICellChangeRecord {
  field: string;
  rowId: string;
  rawValue: string | null;
  currentValue: string | null;
  changedValue: string | null;
  restoreValue?: string | null;
  restoreCellMeta?: IResultCell;
}

export interface IHandleViewUpdateDataParams {
  tableInstance: ITableInstance;
  col: number;
  row: number;
  cellMeta?: IResultCell;
  field?: string;
}

export interface IHandleViewRowDetailParams {
  tableInstance: ITableInstance;
  col: number;
  row: number;
  field?: string;
}

// Callback after some operations on the table
export interface ITableOperationUtils {
  // Copy as sql statement such as insert, update, where
  copyGenerateSQL: (operations: any) => void;
  // Copy as SQL IN list of values
  copyGenerateInValues?: (operations: any) => void;
  // View or modify data
  handleViewUpdateData?: (params: IHandleViewUpdateDataParams) => void;
  // View single line details
  handleViewRowDetail?: (params: IHandleViewRowDetailParams) => void;
}
