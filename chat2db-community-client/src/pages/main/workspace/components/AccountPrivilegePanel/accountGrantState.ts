import type { AccountPrivilege } from '@/service/accountAdmin';

export type AccountGrantSource = 'GLOBAL' | 'DATABASE' | 'TABLE';

export interface DirectColumnGrant {
  databaseName: string;
  tableName: string;
  privilege: string;
  columns: string[];
  grantOption: boolean;
}

export interface InheritedPrivilegeGrant {
  databaseName?: string;
  tableName?: string;
  privilege: string;
  source: AccountGrantSource;
  grantOption: boolean;
}

export interface AccountGrantState {
  directColumnGrants: DirectColumnGrant[];
  inheritedPrivilegeGrants: InheritedPrivilegeGrant[];
}

export function parseAccountGrantState(lines: string[]): AccountGrantState {
  const state: AccountGrantState = { directColumnGrants: [], inheritedPrivilegeGrants: [] };
  for (const line of lines) {
    const parsed = parseGrantLine(line);
    if (!parsed) {
      continue;
    }
    const { databaseName, tableName, grantOption } = parsed;
    for (const clause of parsed.privileges) {
      if (clause.columns.length) {
        if (databaseName !== '*' && tableName !== '*') {
          state.directColumnGrants.push({
            databaseName,
            tableName,
            privilege: clause.privilege,
            columns: clause.columns,
            grantOption,
          });
        }
        continue;
      }
      state.inheritedPrivilegeGrants.push({
        databaseName: databaseName === '*' ? undefined : databaseName,
        tableName: tableName === '*' ? undefined : tableName,
        privilege: clause.privilege,
        source: databaseName === '*' ? 'GLOBAL' : tableName === '*' ? 'DATABASE' : 'TABLE',
        grantOption,
      });
    }
  }
  return state;
}

export function directColumnsFor(
  state: AccountGrantState,
  databaseName?: string,
  tableName?: string,
  privilege?: string,
): string[] {
  if (!databaseName || !tableName || !privilege) {
    return [];
  }
  return Array.from(
    new Set(
      state.directColumnGrants
        .filter(
          (grant) =>
            equalsIgnoreCase(grant.databaseName, databaseName) &&
            equalsIgnoreCase(grant.tableName, tableName) &&
            equalsIgnoreCase(grant.privilege, privilege),
        )
        .flatMap((grant) => grant.columns),
    ),
  );
}

export function inheritedSourcesFor(
  state: AccountGrantState,
  databaseName?: string,
  tableName?: string,
  privilege?: string,
): AccountGrantSource[] {
  if (!databaseName || !tableName || !privilege) {
    return [];
  }
  return Array.from(
    new Set(
      state.inheritedPrivilegeGrants
        .filter((grant) => {
          if (!equalsIgnoreCase(grant.privilege, privilege) && grant.privilege !== 'ALL_PRIVILEGES') {
            return false;
          }
          if (grant.source === 'GLOBAL') {
            return true;
          }
          if (!equalsIgnoreCase(grant.databaseName, databaseName)) {
            return false;
          }
          return grant.source === 'DATABASE' || equalsIgnoreCase(grant.tableName, tableName);
        })
        .map((grant) => grant.source),
    ),
  );
}

export function canRevokeDirectColumnGrant(
  state: AccountGrantState,
  databaseName: string | undefined,
  tableName: string | undefined,
  privileges: AccountPrivilege[] | undefined,
  columns: string[] | undefined,
): boolean {
  if (!databaseName || !tableName || !privileges?.length || !columns?.length) {
    return false;
  }
  return privileges.every((privilege) => {
    const direct = directColumnsFor(state, databaseName, tableName, privilege);
    return columns.every((column) => direct.some((item) => equalsIgnoreCase(item, column)));
  });
}

interface ParsedGrantLine {
  databaseName: string;
  tableName: string;
  grantOption: boolean;
  privileges: Array<{ privilege: string; columns: string[] }>;
}

function parseGrantLine(line: string): ParsedGrantLine | null {
  if (!line.toUpperCase().startsWith('GRANT ')) {
    return null;
  }
  const onIndex = findTopLevelKeyword(line, ' ON ', 6);
  if (onIndex < 0) {
    return null;
  }
  const toIndex = findTopLevelKeyword(line, ' TO ', onIndex + 4);
  if (toIndex < 0) {
    return null;
  }
  const scope = splitTopLevel(line.slice(onIndex + 4, toIndex).trim(), '.');
  if (scope.length !== 2) {
    return null;
  }
  const databaseName = unquoteIdentifier(scope[0]);
  const tableName = unquoteIdentifier(scope[1]);
  if (!databaseName || !tableName) {
    return null;
  }
  const privileges = splitTopLevel(line.slice(6, onIndex), ',')
    .map(parsePrivilegeClause)
    .filter((item): item is { privilege: string; columns: string[] } => Boolean(item));
  if (!privileges.length) {
    return null;
  }
  return {
    databaseName,
    tableName,
    privileges,
    grantOption: line.toUpperCase().includes(' WITH GRANT OPTION'),
  };
}

function parsePrivilegeClause(value: string) {
  const clause = value.trim();
  const open = findTopLevelCharacter(clause, '(');
  if (open < 0) {
    return { privilege: normalizePrivilege(clause), columns: [] };
  }
  const close = clause.lastIndexOf(')');
  if (close < open || clause.slice(close + 1).trim()) {
    return null;
  }
  const privilege = normalizePrivilege(clause.slice(0, open));
  const columns = splitTopLevel(clause.slice(open + 1, close), ',')
    .map(unquoteIdentifier)
    .filter((item): item is string => Boolean(item));
  return privilege && columns.length ? { privilege, columns } : null;
}

function normalizePrivilege(value: string) {
  return value
    .trim()
    .replaceAll(/\s+/g, '_')
    .toUpperCase();
}

function unquoteIdentifier(value: string) {
  const trimmed = value.trim();
  if (trimmed === '*') {
    return trimmed;
  }
  if (trimmed.length >= 2 && trimmed.startsWith('`') && trimmed.endsWith('`')) {
    return trimmed.slice(1, -1).replaceAll('``', '`');
  }
  return trimmed || null;
}

function splitTopLevel(value: string, separator: string) {
  const parts: string[] = [];
  let start = 0;
  let parentheses = 0;
  let quoted = false;
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (character === '`') {
      if (quoted && value[index + 1] === '`') {
        index += 1;
      } else {
        quoted = !quoted;
      }
      continue;
    }
    if (quoted) {
      continue;
    }
    if (character === '(') {
      parentheses += 1;
    } else if (character === ')') {
      parentheses = Math.max(0, parentheses - 1);
    } else if (character === separator && parentheses === 0) {
      parts.push(value.slice(start, index));
      start = index + 1;
    }
  }
  parts.push(value.slice(start));
  return parts;
}

function findTopLevelKeyword(value: string, keyword: string, start: number) {
  let parentheses = 0;
  let backtick = false;
  let quote: string | null = null;
  for (let index = start; index <= value.length - keyword.length; index += 1) {
    const character = value[index];
    if (quote) {
      if (character === quote) {
        if (value[index + 1] === quote) {
          index += 1;
        } else {
          quote = null;
        }
      }
      continue;
    }
    if (backtick) {
      if (character === '`') {
        if (value[index + 1] === '`') {
          index += 1;
        } else {
          backtick = false;
        }
      }
      continue;
    }
    if (character === '`') {
      backtick = true;
    } else if (character === "'" || character === '"') {
      quote = character;
    } else if (character === '(') {
      parentheses += 1;
    } else if (character === ')') {
      parentheses = Math.max(0, parentheses - 1);
    } else if (parentheses === 0 && value.slice(index, index + keyword.length).toUpperCase() === keyword) {
      return index;
    }
  }
  return -1;
}

function findTopLevelCharacter(value: string, target: string) {
  let backtick = false;
  for (let index = 0; index < value.length; index += 1) {
    if (value[index] === '`') {
      if (backtick && value[index + 1] === '`') {
        index += 1;
      } else {
        backtick = !backtick;
      }
    } else if (!backtick && value[index] === target) {
      return index;
    }
  }
  return -1;
}

function equalsIgnoreCase(left?: string, right?: string) {
  return left?.toLowerCase() === right?.toLowerCase();
}
