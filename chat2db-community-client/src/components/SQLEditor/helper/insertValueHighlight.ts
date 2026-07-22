import type { IRange } from '../type';
import {
  ISqlEditorHintRangeVO,
  ISqlEditorHintVO,
  InsertValueMappingStatusEnum,
  MarkMessage,
  SimpleInsertValueMapping,
  SqlStatement,
  SqlTypeEnum,
} from '@/typings/sqlParser';

export interface TextRange {
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
}

export interface TextPosition {
  lineNumber: number;
  column: number;
}

export interface InsertValueHintItem {
  rowIndex: number;
  columnIndex: number;
  fieldName: string;
  fieldType?: string;
  label: string;
  range: TextRange;
  active: boolean;
}

export interface InsertValueHintContext {
  rowIndex: number;
  columnIndex: number;
  fieldName: string;
  fieldType?: string;
  hints: InsertValueHintItem[];
  highlightRanges: TextRange[];
  rowRange: TextRange;
  valueRange?: TextRange;
  editingValue: boolean;
}

type GetTextInRange = (range: TextRange) => string | null | undefined;

export function getInsertValueHighlightRanges(statement: SqlStatement | null | undefined, selection: TextRange | null) {
  if (!statement || statement.type !== SqlTypeEnum.INSERT || !selection) {
    return [];
  }

  const mappings = (statement.insertValueMappings || []).filter(isHighlightMapping);
  if (!mappings.length) {
    return [];
  }

  const selectedColumn = mappings.find((mapping) => rangesTouch(getColumnRange(mapping), selection));
  if (selectedColumn) {
    return mappings
      .filter((mapping) => mapping.columnIndex === selectedColumn.columnIndex)
      .map(getValueRange)
      .filter(hasValidRange);
  }

  const selectedValue = mappings.find((mapping) => rangesTouch(getValueRange(mapping), selection));
  if (!selectedValue) {
    return [];
  }

  return [getColumnRange(selectedValue)].filter(hasValidRange);
}

export function getInsertValueHintContext(
  statement: SqlStatement | null | undefined,
  cursorPosition: TextPosition | null | undefined,
  getTextInRange?: GetTextInRange,
): InsertValueHintContext | null {
  if (
    !statement ||
    statement.type !== SqlTypeEnum.INSERT ||
    !cursorPosition ||
    !statement.insertValueMappings?.length
  ) {
    return null;
  }

  const rowMapping = statement.insertValueMappings.find((mapping) => {
    const rowRange = getRowRange(mapping);
    return hasValidRange(rowRange) && isPositionInRange(cursorPosition.lineNumber, cursorPosition.column, rowRange);
  });
  if (!rowMapping) {
    return null;
  }

  const rowRange = getRowRange(rowMapping);
  const rowMappings = statement.insertValueMappings
    .filter((mapping) => mapping.rowIndex === rowMapping.rowIndex)
    .sort((a, b) => a.columnIndex - b.columnIndex);
  const currentMapping = getCurrentInsertValueMapping(rowMappings, cursorPosition);
  if (!currentMapping || currentMapping.mappingStatus === InsertValueMappingStatusEnum.UNMAPPED_VALUE) {
    return null;
  }

  const hintItems = rowMappings
    .map((mapping) => getInsertValueHintItem(statement, mapping, currentMapping, getTextInRange))
    .filter((item): item is InsertValueHintItem => !!item);
  if (!hintItems.length) {
    return null;
  }

  const currentHint = hintItems.find((item) => item.columnIndex === currentMapping.columnIndex);
  if (!currentHint) {
    return null;
  }

  const currentValueRange = getValueRange(currentMapping);
  const currentColumnRange = getColumnRange(currentMapping);
  const currentValueText = hasValidRange(currentValueRange) ? getTextInRange?.(currentValueRange) ?? '' : '';
  const textAfterCursor =
    getTextInRange && isPositionInRange(cursorPosition.lineNumber, cursorPosition.column, rowRange)
      ? getTextInRange({
          startLineNumber: cursorPosition.lineNumber,
          startColumn: cursorPosition.column,
          endLineNumber: rowRange.endLineNumber,
          endColumn: rowRange.endColumn,
        }) ?? ''
      : '';
  return {
    rowIndex: currentMapping.rowIndex,
    columnIndex: currentMapping.columnIndex,
    fieldName: currentHint.fieldName,
    fieldType: currentHint.fieldType,
    hints: hintItems,
    highlightRanges: [currentColumnRange].filter(hasValidRange),
    rowRange,
    valueRange: hasValidRange(currentValueRange) ? currentValueRange : undefined,
    editingValue: isEditingInsertValueSlot(
      currentMapping,
      cursorPosition,
      currentValueRange,
      currentValueText,
      textAfterCursor,
    ),
  };
}

export function shouldAutoShowInsertValueHint(context: InsertValueHintContext | null | undefined) {
  return !!context?.editingValue;
}

export function insertValueHintContextFromEditorHint(
  editorHint: ISqlEditorHintVO | null | undefined,
): InsertValueHintContext | null {
  if (
    !editorHint
    || editorHint.type !== 'INSERT_VALUE'
    || !editorHint.items?.length
  ) {
    return null;
  }

  const rowRange = toTextRange(editorHint.rowRange) || toTextRange(editorHint.statementRange);
  const valueRange = toTextRange(editorHint.valueRange);
  const hints = editorHint.items
    .map((item): InsertValueHintItem | null => {
      const range = toTextRange(item.range) || valueRange || rowRange;
      const fieldName = item.fieldName || item.label;
      if (!fieldName || !range) {
        return null;
      }
      const fieldType = item.fieldType || undefined;
      return {
        rowIndex: toFiniteNumber(item.rowIndex, 0),
        columnIndex: toFiniteNumber(item.columnIndex, 0),
        fieldName,
        ...(fieldType ? { fieldType } : {}),
        label: item.label || (fieldType ? `${fieldName}:${fieldType}` : fieldName),
        range,
        active: !!item.active,
      };
    })
    .filter((item): item is InsertValueHintItem => !!item);

  if (!hints.length) {
    return null;
  }

  const currentHint = hints.find((item) => item.active) || hints[0];
  const resolvedRowRange = rowRange || currentHint.range;
  if (!resolvedRowRange) {
    return null;
  }

  return {
    rowIndex: currentHint.rowIndex,
    columnIndex: currentHint.columnIndex,
    fieldName: currentHint.fieldName,
    fieldType: currentHint.fieldType,
    hints,
    highlightRanges: [currentHint.range].filter(hasValidRange),
    rowRange: resolvedRowRange,
    valueRange: valueRange || undefined,
    editingValue: !!currentHint.active,
  };
}

export function getInsertValueMismatchMarkers(statementList: SqlStatement[] | null | undefined): MarkMessage[] {
  return getInsertValueMismatchMarkersWithMessage(statementList, (expectedCount, actualCount) =>
    expectedCount !== undefined && actualCount !== undefined
      ? `INSERT value count mismatch: expected ${expectedCount}, got ${actualCount}`
      : 'INSERT value count mismatch',
  );
}

export function getInsertValueMismatchMarkersWithMessage(
  statementList: SqlStatement[] | null | undefined,
  getMessage: (expectedCount?: number, actualCount?: number) => string,
): MarkMessage[] {
  return (statementList || []).flatMap((statement) => {
    if (statement.type !== SqlTypeEnum.INSERT || !statement.insertValueMappings?.length) {
      return [];
    }

    const rowValueCounts = getInsertRowValueCounts(statement.insertValueMappings);
    const mismatchGroups = getInsertValueMismatchGroups(statement.insertValueMappings);
    return Array.from(mismatchGroups.values())
      .map((group) => getInsertValueMismatchMarker(group, getMessage, rowValueCounts.get(group.rowIndex)))
      .filter((marker): marker is MarkMessage => !!marker);
  });
}

interface InsertValueMismatchGroup {
  rowIndex: number;
  mappingStatus: InsertValueMappingStatusEnum.UNMAPPED_COLUMN | InsertValueMappingStatusEnum.UNMAPPED_VALUE;
  ranges: IRange[];
}

function getInsertValueMismatchGroups(mappings: SimpleInsertValueMapping[]) {
  return mappings.reduce((groups, mapping) => {
    if (
      mapping.mappingStatus !== InsertValueMappingStatusEnum.UNMAPPED_COLUMN &&
      mapping.mappingStatus !== InsertValueMappingStatusEnum.UNMAPPED_VALUE
    ) {
      return groups;
    }

    const range =
      mapping.mappingStatus === InsertValueMappingStatusEnum.UNMAPPED_COLUMN
        ? getColumnRange(mapping)
        : getValueRange(mapping);
    if (!hasValidRange(range)) {
      return groups;
    }

    const rowIndex = mapping.rowIndex ?? -1;
    const key = `${rowIndex}:${mapping.mappingStatus}`;
    const group = groups.get(key) || {
      rowIndex,
      mappingStatus: mapping.mappingStatus,
      ranges: [],
    };
    group.ranges.push(range);
    groups.set(key, group);
    return groups;
  }, new Map<string, InsertValueMismatchGroup>());
}

function getInsertValueMismatchMarker(
  group: InsertValueMismatchGroup,
  getMessage: (expectedCount?: number, actualCount?: number) => string,
  rowValueCount?: InsertRowValueCount,
): MarkMessage | null {
  const message = rowValueCount ? getMessage(rowValueCount.expectedCount, rowValueCount.actualCount) : getMessage();
  const range = mergeRanges(group.ranges);
  if (!range) {
    return null;
  }

  return {
    startLineNum: range.startLineNumber,
    startColNum: range.startColumn,
    endLineNum: range.endLineNumber,
    endColNum: range.endColumn,
    message,
    type: 'warning',
  };
}

interface InsertRowValueCount {
  expectedCount: number;
  actualCount: number;
}

function getInsertRowValueCounts(mappings: SimpleInsertValueMapping[]) {
  return mappings.reduce((rowValueCounts, mapping) => {
    const rowValueCount = rowValueCounts.get(mapping.rowIndex) || { expectedCount: 0, actualCount: 0 };
    if (
      mapping.mappingStatus === InsertValueMappingStatusEnum.MATCHED ||
      mapping.mappingStatus === InsertValueMappingStatusEnum.UNMAPPED_COLUMN
    ) {
      rowValueCount.expectedCount += 1;
    }
    if (
      mapping.mappingStatus === InsertValueMappingStatusEnum.MATCHED ||
      mapping.mappingStatus === InsertValueMappingStatusEnum.UNMAPPED_VALUE
    ) {
      rowValueCount.actualCount += 1;
    }
    rowValueCounts.set(mapping.rowIndex, rowValueCount);
    return rowValueCounts;
  }, new Map<number, InsertRowValueCount>());
}

function isHighlightMapping(mapping: SimpleInsertValueMapping) {
  return (
    mapping.mappingStatus === InsertValueMappingStatusEnum.MATCHED &&
    hasValidRange(getColumnRange(mapping)) &&
    hasValidRange(getValueRange(mapping))
  );
}

function getColumnRange(mapping: SimpleInsertValueMapping): IRange {
  return {
    startLineNumber: mapping.columnStartRowNum || 0,
    startColumn: mapping.columnStartColNum || 0,
    endLineNumber: mapping.columnEndRowNum || 0,
    endColumn: mapping.columnEndColNum || 0,
  };
}

function getValueRange(mapping: SimpleInsertValueMapping): IRange {
  return {
    startLineNumber: mapping.valueStartRowNum || 0,
    startColumn: mapping.valueStartColNum || 0,
    endLineNumber: mapping.valueEndRowNum || 0,
    endColumn: mapping.valueEndColNum || 0,
  };
}

function getRowRange(mapping: SimpleInsertValueMapping): IRange {
  return {
    startLineNumber: mapping.rowStartRowNum || 0,
    startColumn: mapping.rowStartColNum || 0,
    endLineNumber: mapping.rowEndRowNum || 0,
    endColumn: mapping.rowEndColNum || 0,
  };
}

function toTextRange(range: ISqlEditorHintRangeVO | null | undefined): TextRange | null {
  if (!range) {
    return null;
  }

  const textRange = {
    startLineNumber: toFiniteNumber(range.startLineNumber, 0),
    startColumn: toFiniteNumber(range.startColumn, 0),
    endLineNumber: toFiniteNumber(range.endLineNumber, 0),
    endColumn: toFiniteNumber(range.endColumn, 0),
  };
  return hasValidRange(textRange) ? textRange : null;
}

function toFiniteNumber(value: number | string | null | undefined, fallback: number) {
  const numberValue = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(numberValue) ? numberValue : fallback;
}

function hasValidRange(range: IRange) {
  return (
    range.startLineNumber > 0 &&
    range.startColumn > 0 &&
    range.endLineNumber > 0 &&
    range.endColumn > 0 &&
    comparePosition(range.startLineNumber, range.startColumn, range.endLineNumber, range.endColumn) <= 0
  );
}

function mergeRanges(ranges: IRange[]): IRange | null {
  const validRanges = ranges.filter(hasValidRange);
  if (!validRanges.length) {
    return null;
  }

  return validRanges.reduce((merged, range) => ({
    startLineNumber:
      comparePosition(range.startLineNumber, range.startColumn, merged.startLineNumber, merged.startColumn) < 0
        ? range.startLineNumber
        : merged.startLineNumber,
    startColumn:
      comparePosition(range.startLineNumber, range.startColumn, merged.startLineNumber, merged.startColumn) < 0
        ? range.startColumn
        : merged.startColumn,
    endLineNumber:
      comparePosition(range.endLineNumber, range.endColumn, merged.endLineNumber, merged.endColumn) > 0
        ? range.endLineNumber
        : merged.endLineNumber,
    endColumn:
      comparePosition(range.endLineNumber, range.endColumn, merged.endLineNumber, merged.endColumn) > 0
        ? range.endColumn
        : merged.endColumn,
  }));
}

function rangesIntersect(a: TextRange, b: TextRange) {
  return (
    comparePosition(a.startLineNumber, a.startColumn, b.endLineNumber, b.endColumn) < 0 &&
    comparePosition(b.startLineNumber, b.startColumn, a.endLineNumber, a.endColumn) < 0
  );
}

function rangesTouch(a: TextRange, b: TextRange) {
  if (isCollapsedRange(a)) {
    return isPositionInRange(a.startLineNumber, a.startColumn, b);
  }
  if (isCollapsedRange(b)) {
    return isPositionInRange(b.startLineNumber, b.startColumn, a);
  }
  return rangesIntersect(a, b);
}

function isCollapsedRange(range: TextRange) {
  return range.startLineNumber === range.endLineNumber && range.startColumn === range.endColumn;
}

function isPositionInRange(lineNumber: number, column: number, range: TextRange) {
  return (
    comparePosition(range.startLineNumber, range.startColumn, lineNumber, column) <= 0 &&
    comparePosition(lineNumber, column, range.endLineNumber, range.endColumn) <= 0
  );
}

function getCurrentInsertValueMapping(mappings: SimpleInsertValueMapping[], cursorPosition: TextPosition) {
  const selectedValue = mappings.find((mapping) => {
    const valueRange = getValueRange(mapping);
    return hasValidRange(valueRange) && isPositionInRange(cursorPosition.lineNumber, cursorPosition.column, valueRange);
  });
  if (selectedValue) {
    return selectedValue;
  }

  const firstMissingValue = mappings.find(
    (mapping) => mapping.mappingStatus === InsertValueMappingStatusEnum.UNMAPPED_COLUMN,
  );
  if (firstMissingValue) {
    return firstMissingValue;
  }

  const valueMappings = mappings.filter((mapping) => hasValidRange(getValueRange(mapping)));
  if (!valueMappings.length) {
    return mappings.find((mapping) => mapping.mappingStatus !== InsertValueMappingStatusEnum.UNMAPPED_VALUE) || null;
  }

  return (
    valueMappings.find(
      (mapping) =>
        comparePosition(
          cursorPosition.lineNumber,
          cursorPosition.column,
          getValueRange(mapping).endLineNumber,
          getValueRange(mapping).endColumn,
        ) <= 0,
    ) || valueMappings[valueMappings.length - 1]
  );
}

function isEditingInsertValueSlot(
  mapping: SimpleInsertValueMapping,
  cursorPosition: TextPosition,
  valueRange: TextRange,
  valueText: string,
  textAfterCursor: string,
) {
  if (mapping.mappingStatus === InsertValueMappingStatusEnum.UNMAPPED_COLUMN) {
    return true;
  }
  if (!hasValidRange(valueRange)) {
    return false;
  }
  if (!isPositionInRange(cursorPosition.lineNumber, cursorPosition.column, valueRange)) {
    return false;
  }
  if (isCollapsedRange(valueRange)) {
    return true;
  }
  if (!valueText.trim()) {
    return true;
  }
  if (
    comparePosition(
      cursorPosition.lineNumber,
      cursorPosition.column,
      valueRange.endLineNumber,
      valueRange.endColumn,
    ) < 0
  ) {
    return true;
  }
  return !isFollowedByValueDelimiter(textAfterCursor);
}

function isFollowedByValueDelimiter(text: string) {
  const nextNonSpace = (text || '').trimStart().charAt(0);
  return nextNonSpace === ',' || nextNonSpace === ')';
}

function getInsertValueHintItem(
  statement: SqlStatement,
  mapping: SimpleInsertValueMapping,
  currentMapping: SimpleInsertValueMapping,
  getTextInRange?: GetTextInRange,
): InsertValueHintItem | null {
  const columnRange = getColumnRange(mapping);
  if (!hasValidRange(columnRange) || mapping.mappingStatus === InsertValueMappingStatusEnum.UNMAPPED_VALUE) {
    return null;
  }

  const fieldName = getColumnName(statement, columnRange, getTextInRange);
  if (!fieldName) {
    return null;
  }

  const fieldType = getColumnType(statement, fieldName);
  return {
    rowIndex: mapping.rowIndex,
    columnIndex: mapping.columnIndex,
    fieldName,
    fieldType,
    label: fieldType ? `${fieldName}:${fieldType}` : fieldName,
    range: columnRange,
    active: mapping.columnIndex === currentMapping.columnIndex,
  };
}

function getColumnName(statement: SqlStatement, range: TextRange, getTextInRange?: GetTextInRange) {
  const rawColumnName = (getTextInRange?.(range) ?? getStatementTextInRange(statement, range)).trim();
  return rawColumnName.replace(/,$/, '').trim();
}

function getColumnType(statement: SqlStatement, columnName: string) {
  const normalizedColumnName = normalizeIdentifier(columnName);
  if (!normalizedColumnName) {
    return undefined;
  }

  const matchedColumn = (statement.tableColumns || [])
    .reduce((columns, tableColumn) => {
      columns.push(...(tableColumn.simpleColumns || []));
      return columns;
    }, [] as NonNullable<SqlStatement['tableColumns']>[number]['simpleColumns'])
    .find((column) => normalizeIdentifier(column.columnName) === normalizedColumnName);
  return matchedColumn?.dataType || undefined;
}

function getStatementTextInRange(statement: SqlStatement, range: TextRange) {
  const sqlLines = (statement.sql || '').split(/\r\n|\r|\n/);
  const values: string[] = [];
  for (let lineNumber = range.startLineNumber; lineNumber <= range.endLineNumber; lineNumber += 1) {
    const lineIndex = lineNumber - statement.sqlStartRowNum;
    const line = sqlLines[lineIndex] ?? '';
    const baseColumn = lineNumber === statement.sqlStartRowNum ? statement.sqlStartColNum : 1;
    const startColumn = lineNumber === range.startLineNumber ? range.startColumn : baseColumn;
    const endColumn = lineNumber === range.endLineNumber ? range.endColumn : baseColumn + line.length;
    values.push(line.slice(Math.max(0, startColumn - baseColumn), Math.max(0, endColumn - baseColumn)));
  }
  return values.join('\n');
}

function normalizeIdentifier(identifier: string | null | undefined) {
  const trimmedIdentifier = (identifier || '').trim();
  if (!trimmedIdentifier) {
    return '';
  }
  if (
    (trimmedIdentifier.startsWith('`') && trimmedIdentifier.endsWith('`')) ||
    (trimmedIdentifier.startsWith('"') && trimmedIdentifier.endsWith('"')) ||
    (trimmedIdentifier.startsWith("'") && trimmedIdentifier.endsWith("'")) ||
    (trimmedIdentifier.startsWith('[') && trimmedIdentifier.endsWith(']'))
  ) {
    return trimmedIdentifier.slice(1, -1).toLowerCase();
  }
  return trimmedIdentifier.toLowerCase();
}

function comparePosition(lineA: number, colA: number, lineB: number, colB: number) {
  if (lineA !== lineB) {
    return lineA - lineB;
  }
  return colA - colB;
}
