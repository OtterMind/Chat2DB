import { ISqlEditorHintVO } from '@/typings/sqlParser';

export interface InsertValueAutoFill {
  text: string;
  firstValueLength: number;
  valuesTextLength: number;
}

export function getInsertValueAutoFill(
  sql: string,
  cursor: number,
  editorHints: ISqlEditorHintVO[] | null | undefined,
): InsertValueAutoFill | null {
  const safeCursor = Math.max(0, Math.min(cursor, sql.length));
  if (!/\bvalues?\s*\(\s*$/i.test(sql.substring(0, safeCursor))) {
    return null;
  }

  const hint = (editorHints || []).find((candidate) =>
    candidate.type === 'INSERT_VALUE'
    && candidate.items?.some((item) => item.active)
    && isEmptyRange(candidate.valueRange),
  );
  const items = [...(hint?.items || [])]
    .sort((left, right) => left.columnIndex - right.columnIndex);
  if (!items.length || items.some((item) => !item.defaultValue)) {
    return null;
  }

  const values = items.map((item) => item.defaultValue!);
  const valuesText = values.join(', ');
  const hasClosingParenthesis = /^\s*\)/.test(sql.substring(safeCursor));
  return {
    text: `${valuesText}${hasClosingParenthesis ? '' : ')'}`,
    firstValueLength: values[0].length,
    valuesTextLength: valuesText.length,
  };
}

export function materializeInsertValueAutoFillHints(
  sql: string,
  insertionOffset: number,
  editorHints: ISqlEditorHintVO[] | null | undefined,
): ISqlEditorHintVO[] {
  const targetHint = (editorHints || []).find((hint) =>
    hint.type === 'INSERT_VALUE' && hint.items?.some((item) => item.active),
  );
  const orderedItems = [...(targetHint?.items || [])].sort((left, right) => left.columnIndex - right.columnIndex);
  if (!targetHint || !orderedItems.length || orderedItems.some((item) => !item.defaultValue)) {
    return editorHints || [];
  }

  let valueOffset = Math.max(0, Math.min(insertionOffset, sql.length));
  const rangesByColumnIndex = new Map<number, ISqlEditorHintVO['valueRange']>();
  orderedItems.forEach((item, index) => {
    const valueLength = item.defaultValue!.length;
    rangesByColumnIndex.set(item.columnIndex, rangeAtOffsets(sql, valueOffset, valueOffset + valueLength));
    valueOffset += valueLength + (index < orderedItems.length - 1 ? 2 : 0);
  });

  const items = (targetHint.items || []).map((item) => ({
    ...item,
    range: rangesByColumnIndex.get(item.columnIndex) || item.range,
  }));
  return (editorHints || []).map((hint) => hint === targetHint ? {
    ...hint,
    rowRange: rangeAtOffsets(sql, insertionOffset, valueOffset),
    valueRange: rangesByColumnIndex.get(orderedItems[0].columnIndex),
    items,
  } : hint);
}

export function rematerializeInsertValueHints(
  sql: string,
  editorHints: ISqlEditorHintVO[] | null | undefined,
  previousSql?: string | null,
): ISqlEditorHintVO[] {
  const targetHints = (editorHints || [])
    .filter((hint) => hint.type === 'INSERT_VALUE' && hint.items?.length);
  if (!previousSql || previousSql === sql) {
    return targetHints.flatMap((hint) => rematerializeInsertValueHint(sql, hint));
  }

  const previousRows = findValuesRows(previousSql);
  const currentRows = findValuesRows(sql);
  return targetHints.flatMap((hint) => {
    const preferredOffset = hintRowStartOffset(previousSql, hint);
    const previousRowIndex = nearestRowIndex(previousRows, preferredOffset);
    return rematerializeInsertValueHint(sql, hint, currentRows[previousRowIndex]);
  });
}

export function mergeInsertValueHints(
  sql: string,
  existingHints: ISqlEditorHintVO[] | null | undefined,
  incomingHints: ISqlEditorHintVO[] | null | undefined,
): ISqlEditorHintVO[] {
  const merged = rematerializeInsertValueHints(sql, existingHints);
  (incomingHints || [])
    .filter((hint) => hint.type === 'INSERT_VALUE' && hint.items?.length)
    .forEach((incomingHint) => {
      const incomingOffset = hintRowStartOffset(sql, incomingHint);
      const existingIndex = merged.findIndex((hint) => hintRowStartOffset(sql, hint) === incomingOffset);
      if (existingIndex >= 0) {
        merged[existingIndex] = incomingHint;
      } else {
        merged.push(incomingHint);
      }
    });
  return merged.sort((left, right) => hintRowStartOffset(sql, left) - hintRowStartOffset(sql, right));
}

function rematerializeInsertValueHint(
  sql: string,
  targetHint: ISqlEditorHintVO,
  assignedRanges?: OffsetRange[],
): ISqlEditorHintVO[] {
  const orderedItems = [...(targetHint?.items || [])].sort((left, right) => left.columnIndex - right.columnIndex);
  if (!orderedItems.length || orderedItems.some((item) => !item.defaultValue)) {
    return [];
  }

  const preferredOffset = targetHint.rowRange
    ? offsetAtPosition(sql, targetHint.rowRange.startLineNumber, targetHint.rowRange.startColumn)
    : undefined;
  const matchingRanges = assignedRanges
    || findValuesRow(sql, orderedItems.map((item) => item.defaultValue!), preferredOffset);
  if (!matchingRanges) {
    return [];
  }
  const rangesByColumnIndex = new Map<number, ISqlEditorHintVO['valueRange']>();
  orderedItems.forEach((item, index) => {
    const valueRange = matchingRanges[index];
    if (valueRange && valueRange.start < valueRange.end) {
      rangesByColumnIndex.set(item.columnIndex, rangeAtOffsets(sql, valueRange.start, valueRange.end));
    }
  });
  const items = (targetHint.items || []).map((item) => ({
    ...item,
    range: rangesByColumnIndex.get(item.columnIndex),
  }));
  return [{
    ...targetHint,
    rowRange: rangeAtOffsets(sql, matchingRanges[0].start, matchingRanges[matchingRanges.length - 1].end),
    valueRange: rangesByColumnIndex.get(orderedItems[0].columnIndex),
    items,
  }];
}

function hintRowStartOffset(sql: string, hint: ISqlEditorHintVO): number {
  if (!hint.rowRange) {
    return Number.MAX_SAFE_INTEGER;
  }
  return offsetAtPosition(sql, hint.rowRange.startLineNumber, hint.rowRange.startColumn);
}

interface OffsetRange {
  start: number;
  end: number;
}

function findValuesRow(sql: string, expectedValues: string[], preferredOffset?: number): OffsetRange[] | null {
  const rows = findValuesRows(sql);
  const matchingRows = rows.filter((ranges) =>
    ranges.length === expectedValues.length
    && ranges.every((range, index) =>
      normalizeSqlValue(sql.substring(range.start, range.end)) === normalizeSqlValue(expectedValues[index]),
    ),
  );
  const candidates = preferredOffset === undefined && matchingRows.length ? matchingRows : rows;
  if (!candidates.length) {
    return null;
  }
  if (preferredOffset === undefined) {
    return candidates[0];
  }
  return candidates[nearestRowIndex(candidates, preferredOffset)];
}

function findValuesRows(sql: string): OffsetRange[][] {
  const valuesPattern = /\bvalues?\s*\(/gi;
  const rows: OffsetRange[][] = [];
  let match = valuesPattern.exec(sql);
  while (match) {
    const ranges = scanValuesRow(sql, valuesPattern.lastIndex);
    if (ranges?.length) {
      rows.push(ranges);
    }
    match = valuesPattern.exec(sql);
  }
  return rows;
}

function nearestRowIndex(rows: OffsetRange[][], preferredOffset: number): number {
  if (!rows.length) {
    return -1;
  }
  return rows.reduce((closestIndex, candidate, candidateIndex) =>
    Math.abs(candidate[0].start - preferredOffset) < Math.abs(rows[closestIndex][0].start - preferredOffset)
      ? candidateIndex
      : closestIndex,
  0);
}

function scanValuesRow(sql: string, rowStart: number): OffsetRange[] | null {
  const ranges: OffsetRange[] = [];
  let valueStart = rowStart;
  let depth = 0;
  let singleQuoted = false;
  let doubleQuoted = false;
  let dollarQuote: string | null = null;
  for (let index = rowStart; index < sql.length; index += 1) {
    if (dollarQuote) {
      if (sql.startsWith(dollarQuote, index)) {
        index += dollarQuote.length - 1;
        dollarQuote = null;
      }
      continue;
    }
    const current = sql[index];
    const next = sql[index + 1];
    const delimiter = !singleQuoted && !doubleQuoted ? dollarQuoteDelimiterAt(sql, index) : null;
    if (delimiter) {
      dollarQuote = delimiter;
      index += delimiter.length - 1;
      continue;
    }
    if (singleQuoted && current === '\\' && next !== undefined) {
      index += 1;
      continue;
    }
    if (!doubleQuoted && current === "'") {
      if (singleQuoted && next === "'") {
        index += 1;
      } else {
        singleQuoted = !singleQuoted;
      }
      continue;
    }
    if (!singleQuoted && current === '"') {
      if (doubleQuoted && next === '"') {
        index += 1;
      } else {
        doubleQuoted = !doubleQuoted;
      }
      continue;
    }
    if (singleQuoted || doubleQuoted) {
      continue;
    }
    if (current === '(' || current === '[') {
      depth += 1;
    } else if ((current === ')' || current === ']') && depth > 0) {
      depth -= 1;
    } else if (current === ',' && depth === 0) {
      ranges.push(trimOffsetRange(sql, valueStart, index));
      valueStart = index + 1;
    } else if (current === ')' && depth === 0) {
      ranges.push(trimOffsetRange(sql, valueStart, index));
      return ranges;
    }
  }
  ranges.push(trimOffsetRange(sql, valueStart, sql.length));
  return ranges;
}

function trimOffsetRange(sql: string, start: number, end: number): OffsetRange {
  let trimmedStart = start;
  let trimmedEnd = end;
  while (trimmedStart < trimmedEnd && /\s/.test(sql[trimmedStart])) trimmedStart += 1;
  while (trimmedEnd > trimmedStart && /\s/.test(sql[trimmedEnd - 1])) trimmedEnd -= 1;
  return { start: trimmedStart, end: trimmedEnd };
}

function normalizeSqlValue(value: string): string {
  let normalized = '';
  let singleQuoted = false;
  let doubleQuoted = false;
  let dollarQuote: string | null = null;
  for (let index = 0; index < value.length; index += 1) {
    if (dollarQuote) {
      if (value.startsWith(dollarQuote, index)) {
        normalized += dollarQuote;
        index += dollarQuote.length - 1;
        dollarQuote = null;
      } else {
        normalized += value[index];
      }
      continue;
    }
    const current = value[index];
    const next = value[index + 1];
    const delimiter = !singleQuoted && !doubleQuoted ? dollarQuoteDelimiterAt(value, index) : null;
    if (delimiter) {
      normalized += delimiter;
      dollarQuote = delimiter;
      index += delimiter.length - 1;
      continue;
    }
    if (singleQuoted && current === '\\' && next !== undefined) {
      normalized += current + next;
      index += 1;
      continue;
    }
    if (!doubleQuoted && current === "'") {
      normalized += current;
      if (singleQuoted && next === "'") {
        normalized += next;
        index += 1;
      } else {
        singleQuoted = !singleQuoted;
      }
    } else if (!singleQuoted && current === '"') {
      normalized += current;
      if (doubleQuoted && next === '"') {
        normalized += next;
        index += 1;
      } else {
        doubleQuoted = !doubleQuoted;
      }
    } else if ((singleQuoted || doubleQuoted) || !/\s/.test(current)) {
      normalized += singleQuoted || doubleQuoted ? current : current.toLowerCase();
    }
  }
  return normalized;
}

function dollarQuoteDelimiterAt(value: string, offset: number): string | null {
  if (value[offset] !== '$') {
    return null;
  }
  if (offset > 0 && /[A-Za-z0-9_$]/.test(value[offset - 1])) {
    return null;
  }
  return value.substring(offset).match(/^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$/)?.[0] || null;
}

function rangeAtOffsets(sql: string, startOffset: number, endOffset: number) {
  const start = positionAtOffset(sql, startOffset);
  const end = positionAtOffset(sql, endOffset);
  return {
    startLineNumber: start.lineNumber,
    startColumn: start.column,
    endLineNumber: end.lineNumber,
    endColumn: end.column,
  };
}

function positionAtOffset(sql: string, offset: number) {
  const safeOffset = Math.max(0, Math.min(offset, sql.length));
  let lineNumber = 1;
  let column = 1;
  for (let index = 0; index < safeOffset; index += 1) {
    if (sql[index] === '\n') {
      lineNumber += 1;
      column = 1;
    } else {
      column += 1;
    }
  }
  return { lineNumber, column };
}

function offsetAtPosition(sql: string, lineNumber: number, column: number): number {
  const targetLine = Math.max(1, lineNumber);
  const targetColumn = Math.max(1, column);
  let currentLine = 1;
  let offset = 0;
  while (offset < sql.length && currentLine < targetLine) {
    if (sql[offset] === '\n') {
      currentLine += 1;
    }
    offset += 1;
  }
  return Math.min(sql.length, offset + targetColumn - 1);
}

function isEmptyRange(range: ISqlEditorHintVO['valueRange']): boolean {
  return !!range
    && range.startLineNumber === range.endLineNumber
    && range.startColumn === range.endColumn;
}
