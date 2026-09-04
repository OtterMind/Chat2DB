import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'index.tsx'), 'utf8');
const menuSource = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '../../hooks/useCreateRightClickMenu.tsx'),
  'utf8',
);

assert.match(
  source,
  /getDatabaseInfo\(\{\s*dataSourceId,\s*databaseName\s*\}\)/s,
  'database charset readback must send dataSourceId with databaseName',
);

assert.match(
  menuSource,
  /DatabasePropertiesContent\s+dataSourceId=\{dataSourceId!\}\s+databaseName=\{treeNodeData\.originalTitle\}/s,
  'database properties menu must use the selected tree node name',
);

assert.match(
  menuSource,
  /DatabasePropertiesContent[\s\S]*?footer:\s*null,[\s\S]*?closable:\s*true/,
  'database properties dialog without a footer must expose a close button',
);

assert.match(
  menuSource,
  /\[OperationColumn\.DatabaseProperties\]:[\s\S]*?discard:[\s\S]*?DatabaseCapability\.DATABASE_PROPERTIES/,
  'database properties menu must be hidden for database types without plugin support',
);

assert.match(
  source,
  /previewAlterDatabaseSql\(\{\s*dataSourceId,\s*databaseName,\s*charset:\s*values\.charset,\s*collation:\s*values\.collation\s*\}\)/s,
  'database charset preview must send dataSourceId with databaseName',
);

assert.match(
  source,
  /executeDDL\(\{\s*dataSourceId,\s*sql\s*\}\)/s,
  'database charset execution must keep using the selected dataSourceId',
);

console.log('DatabasePropertiesContent request context tests passed');
