import { DatabaseTypeCode } from '@/constants/common';
import { EditColumnOperationType } from '@/constants/editTable';
import { IColumnItemNew, IIndexIncludeColumnItem, IIndexItem } from '@/typings';

export enum IndexColumnKind {
  COLUMN = 'COLUMN',
  EXPRESSION = 'EXPRESSION',
}

export interface IEditableIndexIncludeColumnItem extends IIndexIncludeColumnItem {
  indexColumnKind?: IndexColumnKind;
}

const FORBIDDEN_EXPRESSION_TOKENS = /(;|--|\/\*|\*\/|#)/;
const BARE_IDENTIFIER_PATTERN = /^`(?:``|[^`])+`$|^[A-Za-z_$][A-Za-z0-9_$]*$/;
const NONDETERMINISTIC_FUNCTION_PATTERN =
  /(^|[^A-Za-z0-9_$])(?:connection_id|current_date|current_time|current_timestamp|curdate|curtime|database|last_insert_id|localtime|localtimestamp|now|rand|sysdate|uuid|uuid_short|version)\s*\(/i;
const MYSQL_FUNCTIONAL_INDEX_MINIMUM_VERSION = { major: 8, minor: 0, patch: 13 };
const MYSQL_VERSION_PATTERN = /(\d+)\.(\d+)\.(\d+)/;
const HIDDEN_GENERATED_COLUMN_PATTERN = /!hidden!/i;

export const supportsMysqlExpressionIndex = (databaseType?: string | null, dbVersion?: string | null): boolean => {
  return databaseType === DatabaseTypeCode.MYSQL && isMysqlFunctionalIndexVersion(dbVersion);
};

export const isMysqlFunctionalIndexVersion = (dbVersion?: string | null): boolean => {
  const match = dbVersion?.match(MYSQL_VERSION_PATTERN);
  if (!match) {
    return false;
  }
  const [, majorValue, minorValue, patchValue] = match;
  const major = Number(majorValue);
  const minor = Number(minorValue);
  const patch = Number(patchValue);
  return (
    major > MYSQL_FUNCTIONAL_INDEX_MINIMUM_VERSION.major ||
    (major === MYSQL_FUNCTIONAL_INDEX_MINIMUM_VERSION.major &&
      (minor > MYSQL_FUNCTIONAL_INDEX_MINIMUM_VERSION.minor ||
        (minor === MYSQL_FUNCTIONAL_INDEX_MINIMUM_VERSION.minor &&
          patch >= MYSQL_FUNCTIONAL_INDEX_MINIMUM_VERSION.patch)))
  );
};

export const isHiddenGeneratedFunctionalIndexColumn = (column: Pick<IColumnItemNew, 'name' | 'generatedColumn'>) => {
  const generatedColumn =
    column.generatedColumn === true || String(column.generatedColumn).toLowerCase() === 'true';
  return generatedColumn && HIDDEN_GENERATED_COLUMN_PATTERN.test(column.name || '');
};

export const getEditableIndexColumns = (columnList: IColumnItemNew[]): IColumnItemNew[] => {
  return columnList.filter((column) => column.name !== null && !isHiddenGeneratedFunctionalIndexColumn(column));
};

export const hasExpressionIndexMutation = (indexList: IIndexItem[] = []): boolean => {
  return indexList.some((index) => {
    return (
      (index.editStatus === EditColumnOperationType.Add || index.editStatus === EditColumnOperationType.Modify) &&
      index.columnList?.some((column) => !!column.expression)
    );
  });
};

export const getIndexColumnKind = (row: Partial<IIndexIncludeColumnItem>): IndexColumnKind => {
  return row.expression && !row.columnName ? IndexColumnKind.EXPRESSION : IndexColumnKind.COLUMN;
};

export const applyIndexColumnKind = (
  row: IEditableIndexIncludeColumnItem,
  kind: IndexColumnKind,
): IEditableIndexIncludeColumnItem => {
  if (kind === IndexColumnKind.EXPRESSION) {
    return {
      ...row,
      indexColumnKind: kind,
      columnName: null,
      subPart: null,
    };
  }
  return {
    ...row,
    indexColumnKind: kind,
    expression: null,
  };
};

export const normalizeIndexIncludeColumn = (row: IEditableIndexIncludeColumnItem): IIndexIncludeColumnItem => {
  const { key, indexColumnKind, ...rest } = row;
  const expression = rest.expression?.trim() || null;
  const columnName = rest.columnName || null;
  return {
    ...rest,
    expression: expression && !columnName ? expression : null,
    columnName,
    subPart: expression && !columnName ? null : rest.subPart ?? null,
  };
};

export const validateMysqlExpressionIndexRows = (rows: IIndexIncludeColumnItem[]): string | null => {
  for (const row of rows) {
    const expression = row.expression?.trim();
    if (!expression) {
      continue;
    }
    if (!isSupportedMysqlIndexExpression(expression)) {
      return expression;
    }
  }
  return null;
};

export const isSupportedMysqlIndexExpression = (expression: string): boolean => {
  const normalized = stripOuterParentheses(expression.trim());
  if (
    !normalized ||
    BARE_IDENTIFIER_PATTERN.test(normalized) ||
    NONDETERMINISTIC_FUNCTION_PATTERN.test(normalized) ||
    FORBIDDEN_EXPRESSION_TOKENS.test(normalized)
  ) {
    return false;
  }
  return hasBalancedExpressionDelimiters(normalized);
};

const stripOuterParentheses = (value: string): string => {
  let normalized = value;
  while (normalized.startsWith('(') && normalized.endsWith(')') && wrapsWholeExpression(normalized)) {
    normalized = normalized.slice(1, -1).trim();
  }
  return normalized;
};

const wrapsWholeExpression = (value: string): boolean => {
  let depth = 0;
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (character === '(') {
      depth += 1;
    } else if (character === ')') {
      depth -= 1;
      if (depth === 0 && index < value.length - 1) {
        return false;
      }
    }
    if (depth < 0) {
      return false;
    }
  }
  return depth === 0;
};

const hasBalancedExpressionDelimiters = (value: string): boolean => {
  let depth = 0;
  let quote: string | null = null;
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (quote) {
      if (character === '\\') {
        index += 1;
        continue;
      }
      if (character === quote) {
        if ((quote === '`' || quote === "'") && value[index + 1] === quote) {
          index += 1;
          continue;
        }
        quote = null;
      }
      continue;
    }
    if (character === "'" || character === '"' || character === '`') {
      quote = character;
    } else if (character === '(') {
      depth += 1;
    } else if (character === ')') {
      depth -= 1;
      if (depth < 0) {
        return false;
      }
    }
  }
  return depth === 0 && quote === null;
};
