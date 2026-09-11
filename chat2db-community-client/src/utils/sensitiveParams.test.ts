import { REDACTED_VALUE, redactSensitiveParams } from './sensitiveParams';

function assertEqual(actual: any, expected: any, message: string) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

const dbeaverImportParams = { file: 'connections.dbp', masterPassword: 's3cret-master' };
const redactedImport = redactSensitiveParams(dbeaverImportParams) as Record<string, unknown>;
assertEqual(
  redactedImport,
  { file: 'connections.dbp', masterPassword: REDACTED_VALUE },
  'the DBeaver master password never reaches error details',
);
assertEqual(
  dbeaverImportParams.masterPassword,
  's3cret-master',
  'redaction returns a copy and leaves the sent parameters untouched',
);

assertEqual(
  redactSensitiveParams({ name: 'mysql', ssh: { user: 'root', password: 'p', passphrase: 'k' } }),
  { name: 'mysql', ssh: { user: 'root', password: REDACTED_VALUE, passphrase: REDACTED_VALUE } },
  'credentials nested in a request are redacted at any depth',
);

assertEqual(
  redactSensitiveParams({ password: '', masterPassword: null, token: undefined, alias: 'mysql' }),
  { password: '', masterPassword: null, token: undefined, alias: 'mysql' },
  'an absent credential is reported as absent instead of being replaced',
);

assertEqual(
  redactSensitiveParams({ tokens: ['a', 'b'] }),
  { tokens: REDACTED_VALUE },
  'a password-like field is replaced as a whole, whatever its shape',
);

assertEqual(
  redactSensitiveParams({ connections: [{ alias: 'mysql', password: 'p' }] }),
  { connections: [{ alias: 'mysql', password: REDACTED_VALUE }] },
  'arrays of request entries are walked',
);

assertEqual(redactSensitiveParams('plain'), 'plain', 'a non-object payload is returned as is');
assertEqual(redactSensitiveParams(null), null, 'a null payload is returned as is');
