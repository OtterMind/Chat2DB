import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const source = fs.readFileSync(
  path.join(path.dirname(fileURLToPath(import.meta.url)), 'dataSource.ts'),
  'utf8',
);
const sqlServerBlock = source.match(
  /type: DatabaseTypeCode\.SQLSERVER,[\s\S]*?baseInfo:/,
)?.[0];

assert.ok(sqlServerBlock);
assert.match(sqlServerBlock, /key: 'sslProtocol',[\s\S]*?value: 'TLSv1'/);
