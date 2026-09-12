import assert from 'node:assert/strict';
import '../../blocks/Setting/McpSetting/mcpLifecycle.test';
import { readFileSync } from 'node:fs';
import { Layers, LayoutDashboard, MessageSquarePlus } from 'lucide-react';

import { CORE_MAIN_NAV_KEYS, createCoreMainNavItems } from './navigationItems';

const items = createCoreMainNavItems({
  stream: { component: 'stream-component', name: 'Stream' },
  workspace: { component: 'workspace-component', name: 'Workspace' },
  dashboard: { component: 'dashboard-component', name: 'Dashboard' },
});

assert.deepEqual(
  items.map((item) => item.key),
  CORE_MAIN_NAV_KEYS,
  'shared main navigation should keep the Stream, Workspace, Dashboard order',
);
assert.strictEqual(items[0].icon, MessageSquarePlus, 'Stream should use the semantic Lucide icon');
assert.strictEqual(items[1].icon, Layers, 'Workspace should use the semantic Lucide icon');
assert.strictEqual(items[2].icon, LayoutDashboard, 'Dashboard should use the semantic Lucide icon');
assert.ok(
  items.every((item) => item.isLoad === false),
  'shared navigation items should remain lazy by default',
);

const communityMainPage = readFileSync('src/pages/main/CommunityMainPage.tsx', 'utf8');
assert.match(communityMainPage, /createCoreMainNavItems/);
assert.doesNotMatch(communityMainPage, /organization|team|upgrade|pricing/i);
assert.doesNotMatch(
  communityMainPage,
  /networkAbandoned/,
  'shared navigation must not hide local Chat or Dashboard based on commercial activation mode',
);

console.log('Main navigation item tests passed.');
