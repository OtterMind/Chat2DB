import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import clientExtension from '@client-extension';
import { mergeNavigationItems } from './merge';
import type { ClientNavigationContribution } from './types';

assert.deepEqual(clientExtension.navigationItems, []);
assert.deepEqual(clientExtension.mainPage.useNavigationItems([]), []);
assert.equal(clientExtension.mainPage.resolveNavigationPage, undefined);
assert.equal(clientExtension.mainPage.actionBarExtras, undefined);
assert.equal(clientExtension.settings, undefined);
assert.equal(clientExtension.resourceOperations, undefined);
assert.equal(clientExtension.knowledgeMentions, undefined);
assert.equal(clientExtension.tableMetadataSearch, undefined);
assert.deepEqual(clientExtension.requestPolicy, {
  permissionDeniedInteraction: 'prompt-application',
});

const coreNavigation = [
  {
    key: 'workspace',
    icon: 'core-workspace-icon',
    name: 'Workspace',
    component: 'core-workspace-panel',
  },
];
const extensionNavigation: ClientNavigationContribution[] = [
  {
    id: 'knowledge',
    icon: 'knowledge-icon',
    name: 'Knowledge',
    component: 'knowledge-panel',
  },
];

assert.deepEqual(
  mergeNavigationItems(coreNavigation, extensionNavigation).map((item) => item.key),
  ['workspace', 'knowledge'],
);
assert.throws(
  () => mergeNavigationItems(coreNavigation, [{ ...extensionNavigation[0], id: 'workspace' }]),
  /Duplicate client contribution id: workspace/,
);
assert.throws(
  () => mergeNavigationItems(coreNavigation, [...extensionNavigation, extensionNavigation[0]]),
  /Duplicate client contribution id: knowledge/,
);

for (const host of ['src/blocks/Setting/index.tsx', 'src/pages/main/CommunityMainPage.tsx']) {
  assert.match(readFileSync(host, 'utf8'), /clientExtension/);
}

const configSource = readFileSync('.umirc.ts', 'utf8');
assert.match(configSource, /'@client-extension':/);
assert.match(configSource, /'@client-runtime':/);
assert.doesNotMatch(configSource, /CHAT2DB_PRODUCT|\b(?:pro|local|enterprise|delivery)\b/i);

const sqlServiceSource = readFileSync('src/service/sql.ts', 'utf8');
assert.doesNotMatch(sqlServiceSource, /\/api\/enterprise/);

console.log('Client extension contract tests passed.');
