import { DatabaseTypeCode } from '@/constants/common';
import { EditColumnOperationType } from '@/constants/editTable';
import type { IColumnItemNew, IEditTableInfo } from '@/typings';

const UNSAFE_GENERATED_EXPRESSION_KEYWORD =
  /\b(ALTER|CALL|CREATE|DELETE|DROP|GRANT|INSERT|LOAD|LOCK|RENAME|REPLACE|REVOKE|TRUNCATE|UNLOCK|UPDATE)\b/i;

export const MYSQL_GENERATED_COLUMN_MIN_VERSION = '5.7.6';

export function canEditMysqlGeneratedColumns(
  databaseType?: DatabaseTypeCode | string | null,
  generatedColumnSupported?: boolean | null,
): boolean {
  return databaseType === DatabaseTypeCode.MYSQL && generatedColumnSupported === true;
}

export function isGeneratedColumn(column?: Pick<IColumnItemNew, 'generatedColumn' | 'generationExpression'> | null) {
  return !!column && (column.generatedColumn === true || !!column.generationExpression?.trim());
}

export function validateGeneratedColumnExpression(expression?: string | null): boolean {
  const value = expression?.trim();
  if (!value) {
    return false;
  }
  if (value.includes(';') || value.includes('/*') || value.includes('*/') || value.includes('--') || value.includes('#')) {
    return false;
  }
  if (!hasBalancedDelimiters(value)) {
    return false;
  }
  return !UNSAFE_GENERATED_EXPRESSION_KEYWORD.test(stripQuotedText(value));
}

export function normalizeGeneratedColumnForSubmit(
  column: IColumnItemNew,
  generatedColumnSupported: boolean,
): IColumnItemNew {
  if (!generatedColumnSupported) {
    return {
      ...column,
      generatedColumn: false,
      generationExpression: null,
      generatedColumnType: null,
    };
  }

  const generationExpression = column.generationExpression?.trim();
  if (!generationExpression) {
    if (column.generatedColumn === true) {
      return {
        ...column,
        defaultValue: null,
        autoIncrement: false,
        onUpdateCurrentTimestamp: false,
        generatedColumn: true,
        generationExpression: null,
        generatedColumnType: canonicalGeneratedColumnType(column.generatedColumnType),
      };
    }
    return {
      ...column,
      generatedColumn: false,
      generationExpression: null,
      generatedColumnType: null,
    };
  }

  return {
    ...column,
    defaultValue: null,
    autoIncrement: false,
    onUpdateCurrentTimestamp: false,
    generatedColumn: true,
    generationExpression,
    generatedColumnType: canonicalGeneratedColumnType(column.generatedColumnType),
  };
}

export function hasGeneratedColumnStorageConversion(oldTable?: IEditTableInfo, newTable?: IEditTableInfo): boolean {
  if (!oldTable?.columnList?.length || !newTable?.columnList?.length) {
    return false;
  }

  return newTable.columnList.some((newColumn) => {
    if (newColumn.editStatus !== EditColumnOperationType.Modify) {
      return false;
    }
    const oldName = newColumn.oldName || newColumn.name;
    const oldColumn = oldTable.columnList.find((column) => column.name === oldName);
    return (
      isGeneratedColumn(oldColumn) &&
      isGeneratedColumn(newColumn) &&
      canonicalGeneratedColumnType(oldColumn?.generatedColumnType) !==
        canonicalGeneratedColumnType(newColumn.generatedColumnType)
    );
  });
}

function canonicalGeneratedColumnType(value?: string | null): 'VIRTUAL' | 'STORED' {
  return value?.toUpperCase() === 'STORED' ? 'STORED' : 'VIRTUAL';
}

function hasBalancedDelimiters(expression: string): boolean {
  let parentheses = 0;
  let quote = '';
  for (let index = 0; index < expression.length; index += 1) {
    const current = expression[index];
    const charCode = current.charCodeAt(0);
    if (charCode <= 31 || charCode === 127) {
      return false;
    }
    if (quote) {
      if (current === '\\') {
        index += 1;
      } else if (current === quote) {
        if (expression[index + 1] === quote) {
          index += 1;
        } else {
          quote = '';
        }
      }
      continue;
    }
    if (current === "'" || current === '"' || current === '`') {
      quote = current;
    } else if (current === '(') {
      parentheses += 1;
    } else if (current === ')') {
      parentheses -= 1;
      if (parentheses < 0) {
        return false;
      }
    }
  }
  return !quote && parentheses === 0;
}

function stripQuotedText(expression: string): string {
  let quote = '';
  let unquoted = '';
  for (let index = 0; index < expression.length; index += 1) {
    const current = expression[index];
    if (quote) {
      if (current === '\\') {
        index += 1;
      } else if (current === quote) {
        if (expression[index + 1] === quote) {
          index += 1;
        } else {
          quote = '';
        }
      }
      continue;
    }
    if (current === "'" || current === '"' || current === '`') {
      quote = current;
      unquoted += ' ';
    } else {
      unquoted += current;
    }
  }
  return unquoted;
}
