/**
 * Finds `:name` and `?` parameter placeholders in the SQL that is about to run.
 *
 * Placeholders inside string literals, quoted identifiers and comments are
 * ignored, as are `::` casts, `:=` assignments and colons that follow an
 * identifier character (`a:b`). Statements that start with CREATE, ALTER or DROP
 * never contain placeholders, so trigger bodies that use `:NEW`/`:OLD` still run
 * as plain SQL.
 *
 * The backend applies the same rules before binding
 * (`ai.chat2db.spi.util.SqlParameterParser` and `SqlParameterSyntax`); keep the
 * two in step. Detection here only decides what to prompt for: values are sent
 * separately and bound by the JDBC driver, never written into the SQL.
 */
import type { ISqlParameterValue, ISqlParameterValues, SqlParameterType } from '@/service/dmlRequest';

export interface SqlParameterSyntax {
  /** `\` escapes the next character inside quotes (MySQL family). */
  backslashEscapes: boolean;
  /** `#` starts a line comment (MySQL family, BigQuery). */
  hashLineComments: boolean;
  /** `$tag$...$tag$` and `E'...'` strings (PostgreSQL family). */
  dollarQuotedStrings: boolean;
  /** Block comments nest (PostgreSQL family). */
  nestedBlockComments: boolean;
  /** `??` is a literal question mark for the JDBC driver (PgJDBC). */
  doubledQuestionMarkEscape: boolean;
  /** `[name]` is a quoted identifier (SQL Server). */
  bracketIdentifiers: boolean;
  /** `q'[...]'` strings (Oracle family). */
  alternativeQuotedStrings: boolean;
  /** `?|` and `?&` are operators, never placeholders (PostgreSQL family). */
  questionMarkOperators: boolean;
}

const ANSI_SYNTAX: SqlParameterSyntax = {
  backslashEscapes: false,
  hashLineComments: false,
  dollarQuotedStrings: false,
  nestedBlockComments: false,
  doubledQuestionMarkEscape: false,
  bracketIdentifiers: false,
  alternativeQuotedStrings: false,
  questionMarkOperators: false,
};

const MYSQL_SYNTAX: SqlParameterSyntax = { ...ANSI_SYNTAX, backslashEscapes: true, hashLineComments: true };
const BACKSLASH_ESCAPE_SYNTAX: SqlParameterSyntax = { ...ANSI_SYNTAX, backslashEscapes: true };
const POSTGRESQL_SYNTAX: SqlParameterSyntax = {
  ...ANSI_SYNTAX,
  dollarQuotedStrings: true,
  nestedBlockComments: true,
  doubledQuestionMarkEscape: true,
  questionMarkOperators: true,
};
const SQLSERVER_SYNTAX: SqlParameterSyntax = { ...ANSI_SYNTAX, bracketIdentifiers: true };
const ORACLE_SYNTAX: SqlParameterSyntax = { ...ANSI_SYNTAX, alternativeQuotedStrings: true };

// BigQuery shares the MySQL lexical rules: backslash escapes and # comments.
const MYSQL_TYPES = new Set(['MYSQL', 'MARIADB', 'OCEANBASE', 'TIDB', 'DORIS', 'STARROCKS', 'BIGQUERY']);
const BACKSLASH_ESCAPE_TYPES = new Set(['CLICKHOUSE', 'HIVE']);
const POSTGRESQL_TYPES = new Set(['POSTGRESQL', 'KINGBASE', 'OPENGAUSS', 'GAUSSDB', 'COCKROACHDB', 'REDSHIFT']);
const ORACLE_TYPES = new Set(['ORACLE', 'OCEANBASE_ORACLE', 'DM', 'SUNDB']);
const UNSUPPORTED_TYPES = new Set(['MONGODB', 'REDIS']);
const DDL_KEYWORDS = new Set(['CREATE', 'ALTER', 'DROP']);

/** Returns the lexical rules for a database type, or `undefined` when it cannot bind SQL parameters. */
export function sqlParameterSyntaxFor(databaseType?: string | null): SqlParameterSyntax | undefined {
  const type = (databaseType || '').trim().toUpperCase();
  if (UNSUPPORTED_TYPES.has(type)) return undefined;
  if (MYSQL_TYPES.has(type)) return MYSQL_SYNTAX;
  if (BACKSLASH_ESCAPE_TYPES.has(type)) return BACKSLASH_ESCAPE_SYNTAX;
  if (POSTGRESQL_TYPES.has(type)) return POSTGRESQL_SYNTAX;
  if (type === 'SQLSERVER') return SQLSERVER_SYNTAX;
  if (ORACLE_TYPES.has(type)) return ORACLE_SYNTAX;
  return ANSI_SYNTAX;
}

export type SqlPlaceholder =
  | { style: 'named'; name: string; start: number; end: number }
  | { style: 'positional'; start: number; end: number };

export interface SqlParameterDescriptor {
  /** Stable form field key. */
  key: string;
  /** Set for `:name` parameters. */
  name?: string;
  /** 1-based position, set for `?` parameters. */
  index?: number;
}

export type SqlParameterDetection =
  | { style: 'none'; parameters: [] }
  | { style: 'named' | 'positional'; parameters: SqlParameterDescriptor[] }
  | { style: 'mixed'; parameters: [] };

/** The typed value entered for each parameter, keyed by `SqlParameterDescriptor.key`. */
export type SqlParameterInput = Record<string, ISqlParameterValue>;

export const SQL_PARAMETER_TYPES: SqlParameterType[] = ['STRING', 'NUMBER', 'BOOLEAN', 'NULL'];

const NUMBER = /^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/;

const NAME_START = /[\p{L}_]/u;
const NAME_PART = /[\p{L}\p{Nd}_]/u;
const IDENTIFIER_PART = /[\p{L}\p{Nd}_$]/u;

const isNameStart = (c: string) => c !== '' && NAME_START.test(c);
const isNamePart = (c: string) => c !== '' && NAME_PART.test(c);
const isIdentifierPart = (c: string) => c !== '' && IDENTIFIER_PART.test(c);
const charAt = (sql: string, index: number) => (index >= 0 && index < sql.length ? sql[index] : '');

function skipLine(sql: string, start: number) {
  let i = start;
  while (i < sql.length && sql[i] !== '\n' && sql[i] !== '\r') i++;
  return i;
}

function skipBlockComment(sql: string, start: number, nested: boolean) {
  let depth = 1;
  let i = start + 2;
  while (i < sql.length) {
    const c = sql[i];
    const next = charAt(sql, i + 1);
    if (nested && c === '/' && next === '*') {
      depth++;
      i += 2;
    } else if (c === '*' && next === '/') {
      depth--;
      i += 2;
      if (depth === 0) return i;
    } else {
      i++;
    }
  }
  return sql.length;
}

function skipComment(sql: string, i: number, syntax: SqlParameterSyntax) {
  const c = sql[i];
  const next = charAt(sql, i + 1);
  if (c === '-' && next === '-') return skipLine(sql, i + 2);
  if (c === '#' && syntax.hashLineComments) return skipLine(sql, i + 1);
  if (c === '/' && next === '*') return skipBlockComment(sql, i, syntax.nestedBlockComments);
  return i;
}

function skipQuoted(sql: string, start: number, quote: string, backslashEscapes: boolean) {
  let i = start + 1;
  while (i < sql.length) {
    const c = sql[i];
    if (backslashEscapes && c === '\\') {
      i += 2;
      continue;
    }
    if (c === quote) {
      if (charAt(sql, i + 1) === quote) {
        i += 2;
        continue;
      }
      return i + 1;
    }
    i++;
  }
  return sql.length;
}

function skipBracketIdentifier(sql: string, start: number) {
  let i = start + 1;
  while (i < sql.length) {
    if (sql[i] === ']') {
      if (charAt(sql, i + 1) === ']') {
        i += 2;
        continue;
      }
      return i + 1;
    }
    i++;
  }
  return sql.length;
}

function skipDollarQuoted(sql: string, start: number) {
  let i = start + 1;
  if (charAt(sql, i) !== '$') {
    if (!isNameStart(charAt(sql, i))) return start;
    while (isNamePart(charAt(sql, i))) i++;
    if (charAt(sql, i) !== '$') return start;
  }
  const tag = sql.slice(start, i + 1);
  const close = sql.indexOf(tag, i + 1);
  return close < 0 ? sql.length : close + tag.length;
}

const ALTERNATIVE_QUOTE_CLOSE: Record<string, string> = { '[': ']', '{': '}', '(': ')', '<': '>' };

function skipAlternativeQuoted(sql: string, quoteIndex: number) {
  if (quoteIndex + 1 >= sql.length) return sql.length;
  const open = sql[quoteIndex + 1];
  const close = ALTERNATIVE_QUOTE_CLOSE[open] || open;
  let i = quoteIndex + 2;
  while (i < sql.length) {
    if (sql[i] === close && charAt(sql, i + 1) === "'") return i + 2;
    i++;
  }
  return sql.length;
}

function skipWord(sql: string, start: number) {
  let i = start;
  while (i < sql.length && isIdentifierPart(sql[i])) i++;
  return i;
}

/** Returns the index after a comment, literal or quoted identifier starting at `i`, or `i`. */
function skipNonCode(sql: string, i: number, syntax: SqlParameterSyntax) {
  const skipped = skipComment(sql, i, syntax);
  if (skipped > i) return skipped;
  const c = sql[i];
  const next = charAt(sql, i + 1);
  const previous = charAt(sql, i - 1);
  switch (c) {
    case "'":
      return skipQuoted(sql, i, "'", syntax.backslashEscapes);
    case '"':
      return skipQuoted(sql, i, '"', syntax.backslashEscapes);
    case '`':
      return skipQuoted(sql, i, '`', false);
    case '[':
      return syntax.bracketIdentifiers ? skipBracketIdentifier(sql, i) : i;
    case '$':
      return syntax.dollarQuotedStrings && !isIdentifierPart(previous) ? skipDollarQuoted(sql, i) : i;
    default:
      break;
  }
  if (isIdentifierPart(previous)) return i;
  if (syntax.dollarQuotedStrings && (c === 'E' || c === 'e') && next === "'") {
    return skipQuoted(sql, i + 1, "'", true);
  }
  if (syntax.alternativeQuotedStrings) {
    let quote = i;
    if ((c === 'N' || c === 'n') && (next === 'Q' || next === 'q')) quote++;
    if ((sql[quote] === 'Q' || sql[quote] === 'q') && charAt(sql, quote + 1) === "'") {
      return skipAlternativeQuoted(sql, quote + 1);
    }
  }
  return i;
}

function startsWithDdlKeyword(sql: string, syntax: SqlParameterSyntax) {
  let i = 0;
  while (i < sql.length) {
    const c = sql[i];
    if (/\s/.test(c) || c === '(' || c === ';') {
      i++;
      continue;
    }
    const skipped = skipComment(sql, i, syntax);
    if (skipped > i) {
      i = skipped;
      continue;
    }
    break;
  }
  if (i >= sql.length || !isNameStart(sql[i])) return false;
  return DDL_KEYWORDS.has(sql.slice(i, skipWord(sql, i)).toUpperCase());
}

/** Returns every placeholder occurrence in source order. */
export function findSqlPlaceholders(sql: string, syntax: SqlParameterSyntax): SqlPlaceholder[] {
  if (!sql || !sql.trim() || startsWithDdlKeyword(sql, syntax)) return [];
  const placeholders: SqlPlaceholder[] = [];
  let i = 0;
  while (i < sql.length) {
    const c = sql[i];
    const next = charAt(sql, i + 1);
    const skipped = skipNonCode(sql, i, syntax);
    if (skipped > i) {
      i = skipped;
      continue;
    }
    if (c === '?') {
      if (
        (syntax.doubledQuestionMarkEscape && next === '?') ||
        (syntax.questionMarkOperators && (next === '|' || next === '&'))
      ) {
        i += 2;
        continue;
      }
      placeholders.push({ style: 'positional', start: i, end: i + 1 });
      i++;
      continue;
    }
    if (c === ':') {
      if (next === ':') {
        i += 2;
        continue;
      }
      if (isNameStart(next) && !isIdentifierPart(charAt(sql, i - 1))) {
        let end = i + 1;
        while (end < sql.length && isNamePart(sql[end])) end++;
        placeholders.push({ style: 'named', name: sql.slice(i + 1, end), start: i, end });
        i = end;
        continue;
      }
      i++;
      continue;
    }
    if (isIdentifierPart(c)) {
      i = skipWord(sql, i);
      continue;
    }
    i++;
  }
  return placeholders;
}

/**
 * Lists the logical parameters to prompt for: one entry per distinct name for
 * `:name` parameters, one entry per `?` for positional parameters.
 */
export function detectSqlParameters(sql: string, databaseType?: string | null): SqlParameterDetection {
  const syntax = sqlParameterSyntaxFor(databaseType);
  if (!syntax) return { style: 'none', parameters: [] };
  const placeholders = findSqlPlaceholders(sql, syntax);
  if (!placeholders.length) return { style: 'none', parameters: [] };
  const hasNamed = placeholders.some((placeholder) => placeholder.style === 'named');
  const hasPositional = placeholders.some((placeholder) => placeholder.style === 'positional');
  if (hasNamed && hasPositional) return { style: 'mixed', parameters: [] };
  if (hasPositional) {
    return {
      style: 'positional',
      parameters: placeholders.map((_, position) => ({ key: `position:${position + 1}`, index: position + 1 })),
    };
  }
  const names: string[] = [];
  placeholders.forEach((placeholder) => {
    if (placeholder.style === 'named' && !names.includes(placeholder.name)) names.push(placeholder.name);
  });
  return { style: 'named', parameters: names.map((name) => ({ key: `name:${name}`, name })) };
}

/**
 * Mirrors the backend value check: an empty `STRING` is valid, `NUMBER` is
 * decimal text, `BOOLEAN` is exactly `true` or `false`, and `NULL` has no value.
 */
export function isValidSqlParameterValue(parameter: ISqlParameterValue): boolean {
  const { type, value } = parameter;
  switch (type) {
    case 'STRING':
      return value !== null && value !== undefined;
    case 'NUMBER':
      return typeof value === 'string' && NUMBER.test(value);
    case 'BOOLEAN':
      return value === 'true' || value === 'false';
    case 'NULL':
      return value === null;
    default:
      return false;
  }
}

/**
 * Builds the request values from the entered inputs: a map by name for `:name`
 * parameters, a list in appearance order for `?` parameters. Every parameter
 * must have a valid entry.
 */
export function buildSqlParameterValues(
  parameters: SqlParameterDescriptor[],
  inputs: SqlParameterInput,
): ISqlParameterValues {
  const values = parameters.map((parameter) => {
    const value = inputs[parameter.key];
    if (!value || !isValidSqlParameterValue(value)) {
      throw new Error(`Invalid SQL parameter ${parameter.name ?? parameter.index}`);
    }
    return { parameter, value: { type: value.type, value: value.type === 'NULL' ? null : value.value } };
  });
  if (values.length && values[0].parameter.name === undefined) {
    return { positionalParameters: values.map(({ value }) => value) };
  }
  const named: Record<string, ISqlParameterValue> = {};
  values.forEach(({ parameter, value }) => {
    named[parameter.name!] = value;
  });
  return { parameters: named };
}
