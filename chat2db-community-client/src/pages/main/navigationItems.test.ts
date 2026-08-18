import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ClipboardList, Layers, LayoutDashboard, MessageSquarePlus } from 'lucide-react';

import { CORE_MAIN_NAV_KEYS, createCoreMainNavItems } from './navigationItems';

const items = createCoreMainNavItems({
  stream: { component: 'stream-component', name: 'Stream' },
  tasks: { component: 'tasks-component', name: 'Tasks' },
  workspace: { component: 'workspace-component', name: 'Workspace' },
  dashboard: { component: 'dashboard-component', name: 'Dashboard' },
});

assert.deepEqual(
  items.map((item) => item.key),
  CORE_MAIN_NAV_KEYS,
  'shared main navigation should keep the Stream, Tasks, Workspace, Dashboard order',
);
assert.strictEqual(items[0].icon, MessageSquarePlus, 'Stream should use the semantic Lucide icon');
assert.strictEqual(items[1].icon, ClipboardList, 'Tasks should use the semantic Lucide icon');
assert.strictEqual(items[2].icon, Layers, 'Workspace should use the semantic Lucide icon');
assert.strictEqual(items[3].icon, LayoutDashboard, 'Dashboard should use the semantic Lucide icon');
assert.ok(
  items.every((item) => item.isLoad === false),
  'shared navigation items should remain lazy by default',
);

for (const page of ['src/pages/main/CommunityMainPage.tsx', 'src/pages/main/index.tsx']) {
  assert.match(
    readFileSync(page, 'utf8'),
    /createCoreMainNavItems/,
    `${page} should consume the shared core navigation definition`,
  );
}

const commercialMainPage = readFileSync('src/pages/main/index.tsx', 'utf8');
assert.match(commercialMainPage, /icon: 'icon-a-xunwen1'/, 'Team should keep its existing iconfont icon');
for (const retiredIconCode of ['icon-chat-alt-21', 'icon-gongxiang-', 'icon-chart-square-bar']) {
  assert.doesNotMatch(
    commercialMainPage,
    new RegExp(retiredIconCode),
    `commercial main navigation should not use ${retiredIconCode}`,
  );
}

console.log('Main navigation item tests passed.');
