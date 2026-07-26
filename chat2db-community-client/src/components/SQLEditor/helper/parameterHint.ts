import { ISqlEditorHintVO } from '@/typings/sqlParser';
import {
  InsertValueHintContext,
  TextPosition,
  TextRange,
  insertValueHintContextFromEditorHint,
} from './insertValueHighlight';
import { RoutineParameterHintContext, routineParameterHintContextFromEditorHint } from './routineParameterHint';

export type ParameterHintSource = 'INSERT_VALUE' | 'ROUTINE_PARAMETER';

export interface ParameterHintItem {
  index: number;
  fieldName: string;
  fieldType?: string;
  label: string;
  range: TextRange;
  active: boolean;
}

export interface ParameterHintContext {
  source: ParameterHintSource;
  anchorRange: TextRange;
  rowRange?: TextRange;
  valueRange?: TextRange;
  items: ParameterHintItem[];
}

export function parameterHintContextFromInsertValue(
  context: InsertValueHintContext | null | undefined,
): ParameterHintContext | null {
  if (!context?.hints?.length) {
    return null;
  }

  return {
    source: 'INSERT_VALUE',
    anchorRange: context.valueRange || context.rowRange,
    rowRange: context.rowRange,
    valueRange: context.valueRange,
    items: context.hints.map((hint) => ({
      index: hint.columnIndex,
      fieldName: hint.fieldName,
      ...(hint.fieldType ? { fieldType: hint.fieldType } : {}),
      label: hint.label,
      range: hint.range,
      active: hint.active,
    })),
  };
}

export function parameterHintContextFromRoutine(
  context: RoutineParameterHintContext | null | undefined,
): ParameterHintContext | null {
  if (!context?.hints?.length) {
    return null;
  }

  return {
    source: 'ROUTINE_PARAMETER',
    anchorRange: context.anchorRange,
    items: context.hints.map((hint) => ({
      index: hint.parameterIndex,
      fieldName: hint.parameterName,
      ...(hint.parameterType ? { fieldType: hint.parameterType } : {}),
      label: hint.label,
      range: hint.range,
      active: hint.active,
    })),
  };
}

export function parameterHintContextFromEditorHints(
  editorHints: ISqlEditorHintVO[] | null | undefined,
  position?: TextPosition | null,
): ParameterHintContext | null {
  const hints = (editorHints || []).filter((hint) => isEditorHintActiveAtPosition(hint, position));
  const routineHint = hints
    .map(routineParameterHintContextFromEditorHint)
    .map(parameterHintContextFromRoutine)
    .map((context) => markActiveParameterHintItem(context, position))
    .find((context): context is ParameterHintContext => !!context);
  if (routineHint) {
    return routineHint;
  }
  return hints
    .map((hint) => parameterHintContextFromInsertValue(insertValueHintContextFromEditorHint(hint)))
    .map((context) => markActiveParameterHintItem(context, position))
    .find((context): context is ParameterHintContext => !!context) || null;
}

function markActiveParameterHintItem(
  context: ParameterHintContext | null,
  position?: TextPosition | null,
): ParameterHintContext | null {
  if (!context || !position) {
    return context;
  }
  const activeIndex = getActiveParameterHintItemIndex(context.items, position);
  if (activeIndex < 0) {
    return context;
  }
  return {
    ...context,
    anchorRange: context.items[activeIndex].range,
    items: context.items.map((item, index) => ({
      ...item,
      active: index === activeIndex,
    })),
  };
}

function getActiveParameterHintItemIndex(items: ParameterHintItem[], position: TextPosition): number {
  const directIndex = items.findIndex((item) => isPositionInRange(position, item.range));
  if (directIndex >= 0) {
    return directIndex;
  }

  const sortedItems = items
    .map((item, index) => ({ item, index }))
    .sort((left, right) =>
      comparePosition(
        left.item.range.startLineNumber,
        left.item.range.startColumn,
        right.item.range.startLineNumber,
        right.item.range.startColumn,
      ),
    );

  const nextItem = sortedItems.find(({ item }) =>
    comparePosition(position.lineNumber, position.column, item.range.startLineNumber, item.range.startColumn) < 0,
  );
  if (nextItem) {
    return nextItem.index;
  }

  return sortedItems[sortedItems.length - 1]?.index ?? -1;
}

function isEditorHintActiveAtPosition(editorHint: ISqlEditorHintVO, position?: TextPosition | null) {
  if (!position) {
    return true;
  }
  const range = editorHint.type === 'ROUTINE_PARAMETER'
    ? editorHint.rowRange
    : editorHint.valueRange || editorHint.rowRange;
  if (!range) {
    return true;
  }
  return isPositionInRange(position, range);
}

function isPositionInRange(position: TextPosition, range: TextRange) {
  return (
    comparePosition(range.startLineNumber, range.startColumn, position.lineNumber, position.column) <= 0 &&
    comparePosition(position.lineNumber, position.column, range.endLineNumber, range.endColumn) <= 0
  );
}

function comparePosition(lineA: number, colA: number, lineB: number, colB: number) {
  if (lineA !== lineB) {
    return lineA - lineB;
  }
  return colA - colB;
}
