import { DatabaseTypeCode } from '@/constants/common';

interface IComposeResultQueryParams {
  databaseType?: DatabaseTypeCode;
  filterValue?: string;
  orderByValue?: string;
  originalSql: string;
}

interface IFunctionCallRange {
  closeParenthesisIndex: number;
  openParenthesisIndex: number;
}

const normalizeMongoDocument = (value: string) => {
  const trimmedValue = value.trim();
  if (trimmedValue.startsWith('{') && trimmedValue.endsWith('}')) {
    return trimmedValue;
  }
  return `{ ${trimmedValue} }`;
};

const stripIdentifierQuotes = (identifier: string) => {
  const trimmedIdentifier = identifier.trim();
  const firstCharacter = trimmedIdentifier[0];
  const lastCharacter = trimmedIdentifier[trimmedIdentifier.length - 1];
  if (
    (firstCharacter === '`' && lastCharacter === '`') ||
    (firstCharacter === '"' && lastCharacter === '"') ||
    (firstCharacter === '[' && lastCharacter === ']')
  ) {
    return trimmedIdentifier.slice(1, -1);
  }
  return trimmedIdentifier;
};

const normalizeMongoSort = (value: string) => {
  const trimmedValue = value.trim();
  if (trimmedValue.startsWith('{') && trimmedValue.endsWith('}')) {
    return trimmedValue;
  }

  const orderByItems = trimmedValue.split(',').map((item) => item.trim());
  const parsedItems = orderByItems.map((item) => item.match(/^(.+?)\s+(asc|desc)$/i));
  if (parsedItems.every(Boolean)) {
    const sortEntries = parsedItems.map((match) => {
      const [, identifier, direction] = match!;
      return `${JSON.stringify(stripIdentifierQuotes(identifier))}: ${direction.toLowerCase() === 'asc' ? 1 : -1}`;
    });
    return `{ ${sortEntries.join(', ')} }`;
  }

  return normalizeMongoDocument(trimmedValue);
};

// Mongo shell filters can contain nested calls and quoted parentheses, so locate the matching call boundary explicitly.
const findFunctionCallRange = (command: string, functionName: string): IFunctionCallRange | null => {
  const functionPattern = new RegExp(`\\.${functionName}\\s*\\(`, 'i');
  const match = functionPattern.exec(command);
  if (!match) {
    return null;
  }

  const openParenthesisIndex = command.indexOf('(', match.index);
  let depth = 0;
  let quote = '';
  let escaped = false;

  for (let index = openParenthesisIndex; index < command.length; index += 1) {
    const character = command[index];
    if (quote) {
      if (escaped) {
        escaped = false;
      } else if (character === '\\') {
        escaped = true;
      } else if (character === quote) {
        quote = '';
      }
      continue;
    }

    if (character === '"' || character === "'" || character === '`') {
      quote = character;
    } else if (character === '(') {
      depth += 1;
    } else if (character === ')') {
      depth -= 1;
      if (depth === 0) {
        return { closeParenthesisIndex: index, openParenthesisIndex };
      }
    }
  }

  return null;
};

const findTopLevelComma = (value: string) => {
  const closingCharacters = new Set(['}', ']', ')']);
  const openingCharacters = new Set(['{', '[', '(']);
  let depth = 0;
  let quote = '';
  let escaped = false;

  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (quote) {
      if (escaped) {
        escaped = false;
      } else if (character === '\\') {
        escaped = true;
      } else if (character === quote) {
        quote = '';
      }
      continue;
    }

    if (character === '"' || character === "'" || character === '`') {
      quote = character;
    } else if (openingCharacters.has(character)) {
      depth += 1;
    } else if (closingCharacters.has(character)) {
      depth -= 1;
    } else if (character === ',' && depth === 0) {
      return index;
    }
  }

  return -1;
};

const replaceMongoFindFilter = (command: string, filterValue: string) => {
  const findCallRange = findFunctionCallRange(command, 'find');
  if (!findCallRange) {
    return command;
  }

  const currentArguments = command.slice(findCallRange.openParenthesisIndex + 1, findCallRange.closeParenthesisIndex);
  const projectionIndex = findTopLevelComma(currentArguments);
  const projection = projectionIndex >= 0 ? currentArguments.slice(projectionIndex) : '';
  return `${command.slice(0, findCallRange.openParenthesisIndex + 1)}${normalizeMongoDocument(
    filterValue,
  )}${projection}${command.slice(findCallRange.closeParenthesisIndex)}`;
};

const replaceOrAppendMongoSort = (command: string, orderByValue: string) => {
  const sortDocument = normalizeMongoSort(orderByValue);
  const sortCallRange = findFunctionCallRange(command, 'sort');
  if (sortCallRange) {
    return `${command.slice(0, sortCallRange.openParenthesisIndex + 1)}${sortDocument}${command.slice(
      sortCallRange.closeParenthesisIndex,
    )}`;
  }

  const trailingSemicolon = command.match(/;\s*$/);
  if (!trailingSemicolon) {
    return `${command}.sort(${sortDocument})`;
  }
  const semicolonIndex = trailingSemicolon.index!;
  return `${command.slice(0, semicolonIndex).trimEnd()}.sort(${sortDocument})${command.slice(semicolonIndex)}`;
};

const composeMongoQuery = (originalSql: string, filterValue: string, orderByValue: string) => {
  let command = originalSql;
  if (filterValue) {
    command = replaceMongoFindFilter(command, filterValue);
  }
  if (orderByValue) {
    command = replaceOrAppendMongoSort(command, orderByValue);
  }
  return command;
};

export const composeResultQuery = ({
  databaseType,
  filterValue = '',
  orderByValue = '',
  originalSql,
}: IComposeResultQueryParams) => {
  const normalizedFilterValue = filterValue.trim();
  const normalizedOrderByValue = orderByValue.trim();
  if (databaseType === DatabaseTypeCode.MONGODB) {
    return composeMongoQuery(originalSql, normalizedFilterValue, normalizedOrderByValue);
  }

  let sql = normalizedFilterValue ? `${originalSql} WHERE ${normalizedFilterValue}` : originalSql;
  sql = normalizedOrderByValue ? `${sql} ORDER BY ${normalizedOrderByValue}` : sql;
  return sql;
};
