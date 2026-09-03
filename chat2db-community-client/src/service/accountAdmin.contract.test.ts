import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const srcRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function readSource(relativePath: string) {
  return readFileSync(path.join(srcRoot, relativePath), 'utf8');
}

function interfaceBody(source: string, name: string) {
  const match = new RegExp(`export interface ${name}[^}]*{([\\s\\S]*?)\\n}`).exec(source);
  assert.ok(match, `${name} interface is present`);
  return match[1];
}

const accountAdminSource = readSource('service/accountAdmin.ts');
const panelSource = readSource('pages/main/workspace/components/AccountPrivilegePanel/index.tsx');

const accountCommandBody = interfaceBody(accountAdminSource, 'AccountCommand');
assert.match(
  accountCommandBody,
  /\bcolumnList\?:\s*string\[\]/,
  'AccountCommand exposes the columnList payload required by column-level grants',
);

assert.match(
  panelSource,
  /const watchedColumnList = Form\.useWatch\('columnList', form\);/,
  'AccountPrivilegePanel watches selected columns',
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
  /\bwatchedColumnList\b/,
  'automatic account SQL previews refresh when the selected column list changes',
);

assert.match(
  panelSource,
  /command\.scope === AccountPrivilegeScope\.COLUMN[\s\S]*?command\.columnList\?\.length/,
  'column-level previews require a selected column list before calling preview',
);
