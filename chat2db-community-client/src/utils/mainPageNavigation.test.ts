import assert from 'node:assert/strict';
import {
  createMainRootRoute,
  DEFAULT_MAIN_PAGE_ACTIVE_TAB,
  readPersistedMainPageActiveTab,
  resolveDesktopInitialMainPage,
  resolveInitialMainPage,
} from './mainPageNavigation';

assert.equal(DEFAULT_MAIN_PAGE_ACTIVE_TAB, 'workspace', 'a new user should start on the workspace entry');
assert.equal(
  resolveInitialMainPage('', 'stream'),
  'stream',
  'the previously selected entry should be restored when the URL has no route',
);
assert.equal(
  resolveInitialMainPage('dashboard', 'stream'),
  'dashboard',
  'an explicit URL route should take precedence over the persisted entry',
);
assert.equal(
  resolveInitialMainPage('', ''),
  'workspace',
  'workspace should be used when neither a route nor a persisted entry exists',
);
assert.equal(
  readPersistedMainPageActiveTab('{"state":{"mainPageActiveTab":"dashboard"}}'),
  'dashboard',
  'the persisted main page should be read from the Zustand storage payload',
);
assert.equal(
  readPersistedMainPageActiveTab('{"state":{"mainPageActiveTab":"tasks"}}'),
  'tasks',
  'the task board should be restored as a main page',
);
assert.equal(
  readPersistedMainPageActiveTab('{"state":{"mainPageActiveTab":"settings"}}'),
  undefined,
  'non-navigation values should not be restored as a main page',
);
assert.equal(
  readPersistedMainPageActiveTab('{invalid-json'),
  undefined,
  'invalid persisted state should fall back without blocking startup',
);
assert.equal(
  readPersistedMainPageActiveTab('{"state":{"mainPageActiveTab":"team"}}', ['stream', 'workspace', 'team']),
  'team',
  'commercial entries can be restored when they are present in the current navigation',
);
assert.equal(
  readPersistedMainPageActiveTab('{"state":{"mainPageActiveTab":"team"}}', ['stream', 'workspace']),
  undefined,
  'persisted entries that are not currently available are ignored',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/', 'dashboard'),
  { page: 'dashboard', pathName: '/dashboard' },
  'desktop root startup should restore the last selected main page',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/workspace', 'dashboard'),
  { page: 'dashboard', pathName: '/dashboard' },
  'a shallow desktop main route should not override the last selected page',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/dashboard', undefined),
  { page: 'dashboard', pathName: '/dashboard' },
  'a shallow route should still initialize a desktop profile without persisted state',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/', 'dashboard', ['workspace']),
  { page: 'workspace', pathName: '/workspace' },
  'an unavailable persisted page should fall back to an available main page',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/dashboard', undefined, ['stream', 'workspace']),
  { page: 'workspace', pathName: '/workspace' },
  'an unavailable shallow route should fall back to the default available main page',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/stream/session-1', 'dashboard'),
  { page: 'stream', pathName: '/stream/session-1' },
  'a chat-session deep link should take precedence over the last selected page',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/stream', 'team', ['stream', 'workspace', 'team']),
  { page: 'team', pathName: '/team' },
  'commercial desktop startup restores the last available edition entry',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/dashboard/share/dashboard-1', 'workspace'),
  { page: 'dashboard', pathName: '/dashboard/share/dashboard-1' },
  'a dashboard deep link should take precedence over the last selected page',
);
assert.deepEqual(
  resolveDesktopInitialMainPage('/settings/terminal', 'dashboard'),
  { page: 'settings', pathName: '/settings/terminal' },
  'a non-main explicit route should keep its full deep-link path',
);
assert.deepEqual(
  createMainRootRoute(true, '@/pages/main/CommunityMainPage'),
  { path: '/', component: '@/pages/main/CommunityMainPage' },
  'the Community root route must let persisted navigation choose the initial page',
);
assert.deepEqual(
  createMainRootRoute(false, 'main'),
  { path: '/', redirect: '/stream' },
  'commercial routing should keep its existing root redirect',
);

console.log('Main page navigation tests passed.');
