import type { IColumnItemNew } from '@/typings';
import { normalizeGeneratedColumnForSubmit } from './generatedColumn';

const DEFAULT_DECIMAL_COLUMN_SIZE = 10;
const DECIMAL_TYPES = new Set(['DECIMAL', 'DECIMAL UNSIGNED']);

function hasDecimalDigits(decimalDigits: IColumnItemNew['decimalDigits']) {
  return decimalDigits !== null && decimalDigits !== undefined && String(decimalDigits).trim() !== '';
}

export function normalizeColumnForSubmit(column: IColumnItemNew, generatedColumnSupported = true): IColumnItemNew {
  const normalizedGeneratedColumn = normalizeGeneratedColumnForSubmit(column, generatedColumnSupported);
  const columnType = normalizedGeneratedColumn.columnType?.toUpperCase();
  if (
    columnType &&
    DECIMAL_TYPES.has(columnType) &&
    normalizedGeneratedColumn.columnSize == null &&
    hasDecimalDigits(normalizedGeneratedColumn.decimalDigits)
  ) {
    return {
      ...normalizedGeneratedColumn,
      columnSize: DEFAULT_DECIMAL_COLUMN_SIZE,
    };
  }
  return normalizedGeneratedColumn;
}
