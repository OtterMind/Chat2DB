import type { IResultCell } from '@/typings';

export function isJsonResultCell(cellMeta?: IResultCell) {
  return cellMeta?.valueType === 'JSON' || cellMeta?.columnType?.toUpperCase().includes('JSON') === true;
}

export function isValidJsonValue(value: string) {
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}
