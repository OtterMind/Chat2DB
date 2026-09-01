import type { ITableInstance } from '@/blocks/CanvasTable/typings';
import { getResultColumnNameAtTableColumn } from '../ResultSetTable/columnState';

export interface ResultSearchQueryContext {
  col: number;
  row: number;
  table: ITableInstance;
}

export function matchResultSearchValue(
  query: string,
  value: unknown,
  context?: ResultSearchQueryContext,
): boolean {
  const headerName =
    context?.table.isHeader(context.col, context.row) === true
      ? getResultColumnNameAtTableColumn(context.table, context.col, context.row)
      : undefined;
  const searchableValue = headerName ?? value;
  return searchableValue !== null && searchableValue !== undefined && String(searchableValue).includes(query);
}
