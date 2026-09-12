import assert from 'node:assert/strict';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings/workspace';
import {
  getHydratedWorkspaceLayout,
  getPersistableActiveConsoleId,
  getPersistableWorkspaceLayout,
  getPersistableWorkspaceTabList,
  createSafeWorkspaceStorage,
  sanitizePersistedWorkspaceTabsState,
} from './workspaceTabPersistence';

const tabs: IWorkspaceTab[] = [
  {
    id: 'markdown',
    type: WorkspaceTabType.LocalSQLFile,
    title: 'README.md',
    uniqueData: {
      filePath: '/tmp/README.md',
      fileExtension: 'md',
      fileCharset: 'UTF-8',
      fileBom: true,
      ddl: '# Hello',
    },
  },
  {
    id: 'image',
    type: WorkspaceTabType.LocalSQLFile,
    title: 'diagram.png',
    uniqueData: {
      filePath: '/tmp/diagram.png',
      fileExtension: 'png',
      filePreviewMimeType: 'image/png',
      filePreviewUrl: 'chat2db-resource://preview/root/image',
    },
  },
  {
    id: 'terminal',
    type: WorkspaceTabType.Terminal,
    title: 'Terminal',
    uniqueData: { terminalSessionId: 'session-1', terminalCwd: '/tmp' },
  },
];

assert.equal(getPersistableWorkspaceTabList(undefined), null, 'undefined tab lists should normalize to null');
assert.equal(getPersistableWorkspaceTabList(null), null, 'null tab lists should remain null');
assert.deepEqual(getPersistableWorkspaceTabList([]), [], 'empty tab lists should remain empty');

assert.deepEqual(
  getPersistableWorkspaceTabList(tabs)?.map((tab) => tab.id),
  ['markdown'],
  'ephemeral binary previews and PTY sessions must not be written to workspace storage',
);
assert.deepEqual(
  getPersistableWorkspaceTabList(tabs)?.[0].uniqueData,
  {
    filePath: '/tmp/README.md',
    fileExtension: 'md',
    fileCharset: 'UTF-8',
    fileBom: true,
  },
  'local file metadata must survive workspace persistence without its content',
);
assert.equal(tabs[0].uniqueData?.ddl, '# Hello', 'persistence filtering must not modify live editor state');

const largeLocalFileContent = 'x'.repeat(Math.ceil(2.8 * 1024 * 1024));
const largeLocalFileTabs: IWorkspaceTab[] = ['sql', 'md', 'txt', 'json'].map((extension) => ({
  id: `large-${extension}`,
  type: WorkspaceTabType.LocalSQLFile,
  title: `large.${extension}`,
  uniqueData: {
    filePath: `/tmp/large.${extension}`,
    fileExtension: extension,
    fileCharset: 'UTF-8',
    ddl: largeLocalFileContent,
  },
}));
const persistedLargeLocalFiles = getPersistableWorkspaceTabList(largeLocalFileTabs);
assert.deepEqual(
  persistedLargeLocalFiles?.map((tab) => tab.uniqueData?.ddl),
  [undefined, undefined, undefined, undefined],
  'all local text file types must omit their content from workspace localStorage',
);
assert.equal(
  JSON.stringify(persistedLargeLocalFiles).length < 4096,
  true,
  'large local files must persist only a small metadata payload',
);
assert.equal(
  largeLocalFileTabs.every((tab) => tab.uniqueData?.ddl === largeLocalFileContent),
  true,
  'persistence filtering must preserve every live local editor value',
);

const consoleTabs: IWorkspaceTab[] = Array.from({ length: 101 }, (_, index) => ({
  id: `console-${index}`,
  type: WorkspaceTabType.CONSOLE,
  title: `Console ${index}`,
}));
const cappedTabs = getPersistableWorkspaceTabList(consoleTabs);

assert.equal(cappedTabs?.length, 100, 'only the 100 most recent tabs should be persisted');
assert.equal(cappedTabs?.[0].id, 'console-1', 'the oldest tab should be discarded first');
assert.equal(cappedTabs?.[99].id, 'console-100', 'the newest tab should be retained');
assert.equal(
  getPersistableActiveConsoleId({ activeConsoleId: 'console-0', workspaceTabList: cappedTabs }),
  'console-1',
  'an active tab removed by the cap should fall back to the first retained tab',
);

const sharedUniqueData = { ddl: 'select 1' };
const circularUniqueData: Record<string, unknown> = { shared: sharedUniqueData };
circularUniqueData.self = circularUniqueData;
const persistableCircularTabs = getPersistableWorkspaceTabList([
  {
    id: 'circular',
    type: WorkspaceTabType.CONSOLE,
    title: 'Circular data',
    uniqueData: circularUniqueData,
  },
  {
    id: 'shared',
    type: WorkspaceTabType.CONSOLE,
    title: 'Shared data',
    uniqueData: sharedUniqueData,
  },
]);

assert.doesNotThrow(
  () => JSON.stringify(persistableCircularTabs),
  'circular runtime objects must not break workspace persistence',
);
assert.equal(
  persistableCircularTabs?.[0].uniqueData?.self,
  undefined,
  'circular references must be removed from persisted tab data',
);
assert.deepEqual(
  persistableCircularTabs?.[1].uniqueData,
  sharedUniqueData,
  'shared non-circular values must remain available in each persisted tab',
);

const storageWriteErrors: unknown[] = [];
let failStorageWrites = true;
let storedWorkspaceValue: string | undefined;
const safeStorage = createSafeWorkspaceStorage(
  {
    getItem: () => null,
    setItem: (_name, value) => {
      if (failStorageWrites) {
        throw new DOMException('Quota exceeded', 'QuotaExceededError');
      }
      storedWorkspaceValue = value;
    },
    removeItem: () => undefined,
  },
  (error) => storageWriteErrors.push(error),
);
assert.doesNotThrow(
  () => safeStorage.setItem('Chat2DB_Workspace_Store', '{}'),
  'workspace persistence failures must not interrupt UI state updates',
);
assert.equal(storageWriteErrors.length, 1, 'workspace persistence failures must still be reported');
failStorageWrites = false;
safeStorage.setItem('Chat2DB_Workspace_Store', '{"state":{}}');
assert.equal(storedWorkspaceValue, '{"state":{}}', 'workspace persistence must recover after a failed write');

const sanitizedLegacyState = sanitizePersistedWorkspaceTabsState({
  workspaceTabList: largeLocalFileTabs,
  activeConsoleId: 'large-sql',
  recentlyClosedWorkspaceTabs: largeLocalFileTabs,
  currentConnectionDetails: { dataSourceId: 42 },
});
assert.equal(
  JSON.stringify(sanitizedLegacyState).includes(largeLocalFileContent),
  false,
  'legacy workspace migration must remove local file content from open and recently closed tabs',
);
assert.equal(sanitizedLegacyState.activeConsoleId, 'large-sql', 'legacy workspace migration must retain the active tab');
assert.deepEqual(
  sanitizedLegacyState.currentConnectionDetails,
  { dataSourceId: 42 },
  'legacy workspace migration must preserve unrelated persisted state',
);

const circularPanelState: Record<string, unknown> = {};
circularPanelState.self = circularPanelState;
const persistableLayout = getPersistableWorkspaceLayout({
  panelLeft: true,
  panelLeftWidth: 260,
  panelRight: circularPanelState,
  panelRightWidth: 300,
  lastPanelLeftWidth: 420,
} as any);

assert.equal(persistableLayout.panelRight, false, 'non-boolean panel state must not reach persisted storage');
assert.doesNotThrow(
  () => JSON.stringify(persistableLayout),
  'runtime click events must not create circular workspace layout state',
);

const migratedClosedLeftPanelLayout = getPersistableWorkspaceLayout({
  panelLeft: false,
  panelLeftWidth: 0,
  panelRight: false,
  panelRightWidth: 300,
  lastPanelLeftWidth: 420,
});

assert.equal(
  migratedClosedLeftPanelLayout.panelLeftWidth,
  0,
  'legacy closed-left-panel state must remain closed after width-based layout migration',
);

assert.equal(
  migratedClosedLeftPanelLayout.lastPanelLeftWidth,
  420,
  'a collapsed left panel must keep its remembered width in persisted storage',
);

const hydratedLegacyLayout = getHydratedWorkspaceLayout(
  {
    panelLeft: true,
    panelLeftWidth: 240,
    panelRight: false,
    panelRightWidth: 300,
    lastPanelLeftWidth: 240,
  },
  {
    panelLeft: false,
    panelLeftWidth: 260,
    panelRight: circularPanelState,
    panelRightWidth: Number.NaN,
  },
);

assert.deepEqual(
  hydratedLegacyLayout,
  {
    panelLeft: false,
    panelLeftWidth: 0,
    lastPanelLeftWidth: 240,
    panelRight: false,
    panelRightWidth: 300,
  },
  'hydration must normalize legacy and malformed panel values before the workspace renders',
);

const legacyHydrationWithoutRememberedWidth = getHydratedWorkspaceLayout(
  {
    panelLeft: true,
    panelLeftWidth: 240,
    panelRight: false,
    panelRightWidth: 300,
    lastPanelLeftWidth: 240,
  },
  {
    panelLeft: false,
    panelLeftWidth: 0,
    panelRight: false,
    panelRightWidth: 300,
  },
);

assert.equal(
  legacyHydrationWithoutRememberedWidth.lastPanelLeftWidth,
  240,
  'legacy persisted layouts without a remembered width fall back to the current width',
);

console.log('workspace tab persistence tests passed');
