import assert from 'node:assert/strict';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings/workspace';
import { getPersistableActiveConsoleId, getPersistableWorkspaceTabList } from './workspaceTabPersistence';

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
  tabs[0].uniqueData,
  'local file charset and BOM metadata must survive workspace persistence',
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

console.log('workspace tab persistence tests passed');
