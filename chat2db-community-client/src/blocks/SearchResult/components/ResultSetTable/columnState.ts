import type { ITableInstance } from '@/blocks/CanvasTable/typings';
import type { IResultCell, ITableHeaderItem } from '@/typings/database';

export interface ResultColumnLike {
  field?: unknown;
  hide?: boolean;
  originalData?: ITableHeaderItem;
}

const normalizeField = (field: ResultColumnLike['field']): string | undefined => {
  if ((typeof field !== 'string' && typeof field !== 'number') || field === '') {
    return undefined;
  }
  return String(field);
};

export function getResultColumnFields(columns: readonly ResultColumnLike[]): string[] {
  return columns.map((column) => normalizeField(column.field)).filter((field): field is string => !!field);
}

export function getVisibleResultColumnFields(columns: readonly ResultColumnLike[]): string[] {
  return columns
    .filter((column) => column.hide !== true)
    .map((column) => normalizeField(column.field))
    .filter((field): field is string => !!field);
}

export function reconcileHiddenResultColumnFields(
  fields: readonly string[],
  hiddenFields: ReadonlySet<string>,
): Set<string> {
  const fieldSet = new Set(fields);
  const next = new Set([...hiddenFields].filter((field) => fieldSet.has(field)));
  if (fields.length > 0 && next.size >= fields.length) {
    next.delete(fields[fields.length - 1]);
  }
  return next;
}

export function canHideResultColumn(
  fields: readonly string[],
  hiddenFields: ReadonlySet<string>,
  field: string,
): boolean {
  return canHideResultColumns(fields, hiddenFields, [field]);
}

export function canHideResultColumns(
  fields: readonly string[],
  hiddenFields: ReadonlySet<string>,
  targetFields: readonly string[],
): boolean {
  const reconciledHiddenFields = reconcileHiddenResultColumnFields(fields, hiddenFields);
  const targetFieldSet = new Set(targetFields);
  const visibleFields = fields.filter((field) => !reconciledHiddenFields.has(field));
  const visibleTargetCount = visibleFields.filter((field) => targetFieldSet.has(field)).length;
  return visibleTargetCount > 0 && visibleTargetCount < visibleFields.length;
}

export function hideResultColumnFields(
  fields: readonly string[],
  hiddenFields: ReadonlySet<string>,
  targetFields: readonly string[],
): Set<string> {
  const next = reconcileHiddenResultColumnFields(fields, hiddenFields);
  if (!canHideResultColumns(fields, next, targetFields)) {
    return next;
  }
  const targetFieldSet = new Set(targetFields);
  fields.forEach((field) => {
    if (targetFieldSet.has(field)) {
      next.add(field);
    }
  });
  return next;
}

export function updateHiddenResultColumnFields(
  fields: readonly string[],
  hiddenFields: ReadonlySet<string>,
  field: string,
  visible: boolean,
): Set<string> {
  const next = reconcileHiddenResultColumnFields(fields, hiddenFields);
  if (!fields.includes(field)) {
    return next;
  }
  if (visible) {
    next.delete(field);
    return next;
  }
  return hideResultColumnFields(fields, next, [field]);
}

export function canFreezeResultColumn(columns: readonly ResultColumnLike[], field: string): boolean {
  return canFreezeResultColumns(columns, [], [field]);
}

export function getNextFrozenResultColumnFields(
  columns: readonly ResultColumnLike[],
  frozenFields: readonly string[],
  targetFields: readonly string[],
): string[] {
  const visibleFields = getVisibleResultColumnFields(columns);
  const targetSet = new Set(targetFields);
  const orderedTargets = visibleFields.filter((field) => targetSet.has(field));
  return [
    ...orderedTargets,
    ...frozenFields.filter((field) => !targetSet.has(field) && visibleFields.includes(field)),
  ];
}

export function canFreezeResultColumns(
  columns: readonly ResultColumnLike[],
  frozenFields: readonly string[],
  targetFields: readonly string[],
): boolean {
  const visibleFields = getVisibleResultColumnFields(columns);
  const next = getNextFrozenResultColumnFields(columns, frozenFields, targetFields);
  if (!next.length || next.length >= visibleFields.length) {
    return false;
  }
  const current = frozenFields.filter((field) => visibleFields.includes(field));
  return next.length !== current.length || next.some((field, index) => field !== current[index]);
}

export function orderResultColumns<T extends ResultColumnLike>(
  columns: readonly T[],
  orderedFields: readonly string[],
): T[] {
  const orderMap = new Map(orderedFields.map((field, index) => [field, index]));
  return columns
    .map((column, sourceIndex) => ({ column, sourceIndex }))
    .sort((left, right) => {
      const leftOrder = orderMap.get(normalizeField(left.column.field) || '') ?? Number.MAX_SAFE_INTEGER;
      const rightOrder = orderMap.get(normalizeField(right.column.field) || '') ?? Number.MAX_SAFE_INTEGER;
      return leftOrder - rightOrder || left.sourceIndex - right.sourceIndex;
    })
    .map(({ column }) => column);
}

export function getResultColumnDisplayOrder(
  baseOrder: readonly string[],
  frozenFields: readonly string[],
): string[] {
  const baseFieldSet = new Set(baseOrder);
  const frozenFieldSet = new Set(frozenFields);
  return [
    ...frozenFields.filter((field) => baseFieldSet.has(field)),
    ...baseOrder.filter((field) => !frozenFieldSet.has(field)),
  ];
}

export function mergeResultColumnOrderFromDisplay(
  baseOrder: readonly string[],
  displayOrder: readonly string[],
  frozenFields: readonly string[],
): string[] {
  const baseFieldSet = new Set(baseOrder);
  const frozenFieldSet = new Set(frozenFields);
  const movableDisplayOrder: string[] = [];
  const movableFieldSet = new Set<string>();

  displayOrder.forEach((field) => {
    if (!baseFieldSet.has(field) || frozenFieldSet.has(field) || movableFieldSet.has(field)) {
      return;
    }
    movableFieldSet.add(field);
    movableDisplayOrder.push(field);
  });

  let movableIndex = 0;
  return baseOrder.map((field) => {
    if (frozenFieldSet.has(field) || !movableFieldSet.has(field)) {
      return field;
    }
    return movableDisplayOrder[movableIndex++] || field;
  });
}

// ResultSetTable renders frozen fields as the visible prefix. Keep the row-number
// column frozen and leave at least one data column scrollable.
export function getResultFrozenColumnCount(
  columns: readonly ResultColumnLike[],
  frozenFields: readonly string[],
): number {
  const visibleFields = getVisibleResultColumnFields(columns);
  const visibleFrozenCount = frozenFields.filter((field) => visibleFields.includes(field)).length;
  return 1 + Math.min(visibleFrozenCount, Math.max(0, visibleFields.length - 1));
}

export function getResultFieldAtTableColumn(
  tableInstance: Pick<ITableInstance, 'getHeaderField'>,
  col: number,
  row = 0,
): string | undefined {
  return normalizeField(tableInstance.getHeaderField(col, row) as string | number | undefined);
}

export function getResultColumnAtTableColumn<T extends ResultColumnLike>(
  tableInstance: Pick<ITableInstance, 'getHeaderField'> & { columns?: readonly T[] },
  col: number,
  row = 0,
): T | undefined {
  const field = getResultFieldAtTableColumn(tableInstance, col, row);
  return field === undefined
    ? undefined
    : tableInstance.columns?.find((column) => normalizeField(column.field) === field);
}

export function getResultColumnNameAtTableColumn(
  tableInstance: Pick<ITableInstance, 'getHeaderField'> & { columns?: readonly ResultColumnLike[] },
  col: number,
  row = 0,
): string | undefined {
  return getResultColumnAtTableColumn(tableInstance, col, row)?.originalData?.name;
}

export function getResultCellMetaAtTableColumn(
  tableInstance: Pick<ITableInstance, 'getHeaderField'>,
  record: { __CHAT2DB_CELL_META__?: IResultCell[] } | undefined,
  col: number,
  row = 0,
): IResultCell | undefined {
  const field = getResultFieldAtTableColumn(tableInstance, col, row);
  const sourceIndex = field === undefined ? Number.NaN : Number(field);
  return Number.isInteger(sourceIndex) ? record?.__CHAT2DB_CELL_META__?.[sourceIndex] : undefined;
}
