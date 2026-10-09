import {
  buildSqlParameterValues,
  detectSqlParameters,
  findSqlPlaceholders,
  isValidSqlParameterValue,
  sqlParameterSyntaxFor,
  type SqlParameterDetection,
} from './sqlParameters';
import type { SqlParameterType } from '@/service/dmlRequest';

function assertEqual(actual: unknown, expected: unknown, message: string) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

function assertThrows(run: () => unknown, message: string) {
  try {
    run();
  } catch {
    return;
  }
  throw new Error(`${message}: expected an error`);
}

function logicalParameters(detection: SqlParameterDetection) {
  return detection.parameters.map((parameter) => parameter.name ?? parameter.index);
}

function detect(sql: string, databaseType = 'MYSQL') {
  return detectSqlParameters(sql, databaseType);
}

// Named parameters.
assertEqual(logicalParameters(detect('SELECT * FROM users WHERE id = :id;')), ['id'], 'detects a named parameter');
assertEqual(
  logicalParameters(detect('SELECT *\nFROM users\nWHERE id = :id OR manager_id = :id;')),
  ['id'],
  'shows one input for a repeated named parameter',
);
assertEqual(
  findSqlPlaceholders('SELECT * FROM users WHERE id = :id OR manager_id = :id', sqlParameterSyntaxFor('MYSQL')!).length,
  2,
  'keeps every occurrence of a repeated named parameter for binding',
);
assertEqual(
  logicalParameters(detect('SELECT *\nFROM users\nWHERE id = :id AND status = :status;')),
  ['id', 'status'],
  'detects multiple named parameters in order',
);
assertEqual(
  logicalParameters(detect('SELECT 1 WHERE a = :user_id AND b = :email AND c > :created_at')),
  ['user_id', 'email', 'created_at'],
  'accepts underscores in parameter names',
);
assertEqual(detect('SELECT * FROM users WHERE id = :id').style, 'named', 'reports the named style');

// Positional parameters.
assertEqual(logicalParameters(detect('SELECT * FROM users WHERE id = ?;')), [1], 'detects a positional parameter');
assertEqual(
  logicalParameters(detect('SELECT *\nFROM users\nWHERE name = ? AND age > ?;')),
  [1, 2],
  'treats each question mark as a separate parameter',
);
assertEqual(detect('SELECT ?').style, 'positional', 'reports the positional style');

// Literals, identifiers and comments.
assertEqual(logicalParameters(detect("SELECT ':id', '?';")), [], 'ignores markers in string literals');
assertEqual(logicalParameters(detect("SELECT 'it''s :id ?'", 'POSTGRESQL')), [], 'handles doubled quotes');
assertEqual(logicalParameters(detect("SELECT 'it\\'s :id ?'")), [], 'handles MySQL backslash escapes');
assertEqual(logicalParameters(detect('SELECT "a:id?", `b:id?` FROM t')), [], 'ignores quoted identifiers');
assertEqual(logicalParameters(detect('-- :id\nSELECT * FROM users;')), [], 'ignores line comments');
assertEqual(
  logicalParameters(detect('SELECT *\nFROM users\n-- WHERE id = :id\nWHERE active = true;')),
  [],
  'ignores a commented-out condition',
);
assertEqual(logicalParameters(detect('/*\n :id\n*/\nSELECT * FROM users;')), [], 'ignores block comments');
assertEqual(logicalParameters(detect('SELECT 1 # :id')), [], 'ignores MySQL hash comments');
assertEqual(logicalParameters(detect('SELECT 1 # :id', 'POSTGRESQL')), ['id'], 'keeps # as an operator in PostgreSQL');
assertEqual(
  logicalParameters(detect('/* :skip */ SELECT * FROM t -- ?\nWHERE id = :id')),
  ['id'],
  'still finds parameters after comments',
);

// Dialect-specific syntax.
assertEqual(
  logicalParameters(detect('SELECT :id::int, created::date FROM t', 'POSTGRESQL')),
  ['id'],
  'does not treat PostgreSQL casts as parameters',
);
assertEqual(
  logicalParameters(detect('SELECT $$ :id ? $$, $tag$ :x $tag$', 'POSTGRESQL')),
  [],
  'ignores PostgreSQL dollar-quoted strings',
);
assertEqual(
  logicalParameters(detect('SELECT 1 /* outer /* :inner */ :still_comment */', 'POSTGRESQL')),
  [],
  'handles nested PostgreSQL comments',
);
assertEqual(logicalParameters(detect("SELECT data ?? 'key' FROM t", 'POSTGRESQL')), [], 'keeps ?? as an operator');
assertEqual(logicalParameters(detect('SELECT [weird:id?] FROM t', 'SQLSERVER')), [], 'ignores SQL Server brackets');
assertEqual(logicalParameters(detect("SELECT q'[it's :id ?]' FROM dual", 'ORACLE')), [], 'ignores Oracle q-quotes');
assertEqual(logicalParameters(detect('SET @a := 1')), [], 'ignores := assignments');
assertEqual(logicalParameters(detect('SELECT payload:field FROM t', 'SNOWFLAKE')), [], 'ignores path colons');
assertEqual(logicalParameters(detect("SELECT '12:30:00'")), [], 'ignores times inside strings');
assertEqual(
  logicalParameters(detect('CREATE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW BEGIN :NEW.id := 1; END;', 'ORACLE')),
  [],
  'leaves trigger bodies as plain SQL',
);

// Mixed styles, plain SQL and unsupported databases.
assertEqual(
  detect('SELECT *\nFROM users\nWHERE id = :id AND status = ?;'),
  { style: 'mixed', parameters: [] },
  'rejects mixed parameter styles',
);
assertEqual(detect('SELECT *\nFROM users;'), { style: 'none', parameters: [] }, 'leaves ordinary SQL unchanged');
assertEqual(detect('GET user:id', 'REDIS'), { style: 'none', parameters: [] }, 'skips Redis commands');
assertEqual(detect('db.users.find({ a: "?" })', 'MONGODB'), { style: 'none', parameters: [] }, 'skips MongoDB');
assertEqual(detect('', 'MYSQL'), { style: 'none', parameters: [] }, 'handles empty SQL');

// Selected SQL: only the text being run is scanned.
const editorText = 'SELECT * FROM users WHERE id = :id;\n\nSELECT * FROM orders WHERE user_id = :user_id;';
assertEqual(
  logicalParameters(detect(editorText.slice(0, editorText.indexOf('\n')))),
  ['id'],
  'scans only the selected statement',
);

// PostgreSQL question-mark operators.
assertEqual(
  logicalParameters(detect("SELECT data ?| array['a'], data ?& array['b'] FROM t", 'POSTGRESQL')),
  [],
  'keeps ?| and ?& as PostgreSQL operators',
);
assertEqual(
  logicalParameters(detect("SELECT * FROM t WHERE data ?| array['a'] AND id = ?", 'POSTGRESQL')),
  [1],
  'still detects a real placeholder next to a PostgreSQL operator',
);
assertEqual(logicalParameters(detect("SELECT ?|| 'x'")), [1], 'treats ?| as a placeholder outside PostgreSQL');

// Typed request values.
const named = detect('SELECT * FROM users WHERE id = :id AND nickname = :nickname AND note = :note');
assertEqual(
  buildSqlParameterValues(named.parameters, {
    'name:id': { type: 'NUMBER', value: '123' },
    'name:nickname': { type: 'NULL', value: null },
    'name:note': { type: 'STRING', value: '' },
  }),
  {
    parameters: {
      id: { type: 'NUMBER', value: '123' },
      nickname: { type: 'NULL', value: null },
      note: { type: 'STRING', value: '' },
    },
  },
  'sends named values as a typed map and keeps NULL and the empty string distinct',
);
const positional = detect('SELECT * FROM users WHERE name = ? AND age > ?');
assertEqual(
  buildSqlParameterValues(positional.parameters, {
    'position:1': { type: 'STRING', value: 'alice' },
    'position:2': { type: 'NUMBER', value: '30' },
  }),
  {
    positionalParameters: [
      { type: 'STRING', value: 'alice' },
      { type: 'NUMBER', value: '30' },
    ],
  },
  'sends positional values as a typed list in appearance order',
);
assertThrows(
  () => buildSqlParameterValues(positional.parameters, { 'position:1': { type: 'STRING', value: 'alice' } }),
  'refuses to build values while a parameter is missing',
);
assertThrows(
  () =>
    buildSqlParameterValues(positional.parameters, {
      'position:1': { type: 'STRING', value: 'alice' },
      'position:2': { type: 'NUMBER', value: 'thirty' },
    }),
  'refuses to build an invalid number',
);

// Value checks mirror the backend.
const valid = (type: SqlParameterType, value: string | null) => isValidSqlParameterValue({ type, value });
assertEqual(
  ['42', '-1', '+3.50', '.5', '1e10'].map((value) => valid('NUMBER', value)),
  [true, true, true, true, true],
  'accepts decimal numbers',
);
assertEqual(
  ['', ' 1', '1,000', 'abc', 'NaN'].map((value) => valid('NUMBER', value)),
  [false, false, false, false, false],
  'rejects text that is not a number',
);
assertEqual(
  [valid('BOOLEAN', 'true'), valid('BOOLEAN', 'false'), valid('BOOLEAN', 'TRUE'), valid('BOOLEAN', '')],
  [true, true, false, false],
  'accepts only true and false as booleans',
);
assertEqual(
  [valid('STRING', ''), valid('STRING', null), valid('NULL', null), valid('NULL', '')],
  [true, false, true, false],
  'accepts the empty string and keeps NULL valueless',
);

console.log('sqlParameters tests passed');
