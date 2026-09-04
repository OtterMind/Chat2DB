import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const srcRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function readSource(relativePath: string) {
  return readFileSync(path.join(srcRoot, relativePath), 'utf8');
}

function enumBody(source: string, name: string) {
  const match = new RegExp(`export enum ${name}\\b[^}]*{([\\s\\S]*?)\\n}`).exec(source);
  assert.ok(match, `${name} enum is present`);
  return match[1];
}

function interfaceBody(source: string, name: string) {
  const match = new RegExp(`export interface ${name}[^}]*{([\\s\\S]*?)\\n}`).exec(source);
  assert.ok(match, `${name} interface is present`);
  return match[1];
}

const accountAdminSource = readSource('service/accountAdmin.ts');
const panelSource = readSource('pages/main/workspace/components/AccountPrivilegePanel/index.tsx');

const privilegeBody = enumBody(accountAdminSource, 'AccountPrivilege');
assert.match(
  privilegeBody,
  /\bALTER_ROUTINE\s*=\s*'ALTER_ROUTINE'/,
  'AccountPrivilege exposes ALTER_ROUTINE for routine-scope grants',
);

const accountCommandBody = interfaceBody(accountAdminSource, 'AccountCommand');
assert.match(
  accountCommandBody,
  /\bobjectName\?:\s*string/,
  'AccountCommand exposes the objectName payload required by routine-level grants',
);

assert.match(
  accountAdminSource,
  /AccountGrantSource[\s\S]*DIRECT_ROUTINE[\s\S]*INHERITED_DATABASE[\s\S]*INHERITED_GLOBAL[\s\S]*INHERITED_ROLE/,
  'AccountGrantSource labels direct routine grants separately from inherited access',
);

assert.match(
  accountAdminSource,
  /\/api\/rdb\/account\/grant-summary/,
  'accountAdmin exposes the structured grant summary endpoint',
);

assert.match(
  panelSource,
  /const watchedObjectName = Form\.useWatch\('objectName', form\);/,
  'AccountPrivilegePanel watches selected routine objects',
);

const previewCallIndex = panelSource.indexOf('.preview(readyCommand)');
assert.notEqual(previewCallIndex, -1, 'AccountPrivilegePanel has an automatic preview effect');
const dependencyStart = panelSource.indexOf('  }, [', previewCallIndex);
const dependencyEnd = panelSource.indexOf('  ]);', dependencyStart);
assert.ok(dependencyStart > previewCallIndex, 'preview effect dependency list is present');
assert.ok(dependencyEnd > dependencyStart, 'preview effect dependency list is complete');
const previewDependencies = panelSource.slice(dependencyStart, dependencyEnd);
assert.match(
  previewDependencies,
  /\bwatchedObjectName\b/,
  'automatic account SQL previews refresh when the selected routine object changes',
);

assert.match(
  panelSource,
  /\(command\.scope === AccountPrivilegeScope\.FUNCTION \|\| command\.scope === AccountPrivilegeScope\.PROCEDURE\)[\s\S]*?!command\.objectName/,
  'routine-level previews require a selected object before calling preview',
);

assert.match(
  panelSource,
  /routineRevokeBlocked[\s\S]*disabled=\{!selectedAccount \|\| !previewState \|\| routineRevokeBlocked\}/,
  'routine revokes are blocked unless direct revocable grants are loaded',
);

assert.match(
  panelSource,
  /accountAdminService\s*\n\s*\.grantSummary/,
  'AccountPrivilegePanel loads SHOW GRANTS summaries for selected accounts',
);
