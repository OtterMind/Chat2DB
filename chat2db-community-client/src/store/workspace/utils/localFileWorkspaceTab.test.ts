import assert from 'node:assert/strict';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IBoundInfo, IWorkspaceTab } from '@/typings/workspace';
import { getRestoredLocalFileReadRequest, refreshLocalFileWorkspaceTab } from './localFileWorkspaceTab';

const originalUniqueData = Object.freeze({
  filePath: '/tmp/report.pdf',
  fileExtension: 'pdf',
  filePreviewUrl: 'chat2db-resource://preview/root/old',
  filePreviewMimeType: 'application/pdf',
});
const existingTab = Object.freeze({
  id: 'pdf-tab',
  type: WorkspaceTabType.LocalSQLFile,
  title: 'report.pdf',
  uniqueData: originalUniqueData,
});
const otherTab = Object.freeze({
  id: 'other-tab',
  type: WorkspaceTabType.LocalSQLFile,
  title: 'query.sql',
  uniqueData: Object.freeze({ filePath: '/tmp/query.sql', fileExtension: 'sql', ddl: 'select 1' }),
});
const frozenTabs = Object.freeze([existingTab, otherTab]) as unknown as IWorkspaceTab[];
const nextUniqueData: IBoundInfo = {
  filePath: '/tmp/report.pdf',
  fileExtension: undefined,
  filePreviewUrl: 'chat2db-resource://preview/root/new',
  filePreviewMimeType: 'application/pdf',
};

const result = refreshLocalFileWorkspaceTab(frozenTabs, '/tmp/report.pdf', nextUniqueData);

assert.ok(result, 'an already-open local file should produce an immutable refresh result');
assert.equal(result.activeTabId, 'pdf-tab');
assert.notEqual(result.workspaceTabList, frozenTabs, 'the workspace tab list must be replaced');
assert.notEqual(result.workspaceTabList[0], existingTab, 'the matching tab must be replaced');
assert.notEqual(result.workspaceTabList[0].uniqueData, originalUniqueData, 'the matching uniqueData must be replaced');
assert.equal(result.workspaceTabList[0].uniqueData?.filePreviewUrl, 'chat2db-resource://preview/root/new');
assert.equal(result.workspaceTabList[0].uniqueData?.fileExtension, 'pdf', 'the previous extension must be retained');
assert.equal(result.workspaceTabList[1], otherTab, 'unmatched tabs should retain their references');
assert.equal(originalUniqueData.filePreviewUrl, 'chat2db-resource://preview/root/old', 'the frozen source must not change');
assert.equal(refreshLocalFileWorkspaceTab(frozenTabs, '/tmp/missing.sql', nextUniqueData), undefined);

const duplicateTab = Object.freeze({
  ...existingTab,
  id: 'pdf-tab-copy',
  uniqueData: Object.freeze({ ...originalUniqueData }),
});
const duplicateTabs = Object.freeze([existingTab, duplicateTab]) as unknown as IWorkspaceTab[];
const targetedResult = refreshLocalFileWorkspaceTab(
  duplicateTabs,
  '/tmp/report.pdf',
  nextUniqueData,
  'pdf-tab-copy',
);

assert.ok(targetedResult, 'a targeted duplicate tab should be refreshed');
assert.equal(targetedResult.activeTabId, 'pdf-tab-copy');
assert.equal(targetedResult.workspaceTabList[0], existingTab, 'the first tab with the same path must stay unchanged');
assert.equal(targetedResult.workspaceTabList[1].uniqueData?.filePreviewUrl, 'chat2db-resource://preview/root/new');
assert.equal(
  refreshLocalFileWorkspaceTab(duplicateTabs, '/tmp/report.pdf', nextUniqueData, 'closed-tab'),
  undefined,
  'a closed target tab must not fall back to another tab with the same path',
);

const restoredReadRequest = getRestoredLocalFileReadRequest({
  id: 'restored-sql',
  type: WorkspaceTabType.LocalSQLFile,
  title: 'restored.sql',
  uniqueData: {
    filePath: '/tmp/root/restored.sql',
    fileExtension: 'sql',
    fileRootToken: 'root-token',
    fileRelativePath: 'nested/restored.sql',
    fileCharset: 'GB18030',
  },
});
assert.deepEqual(
  restoredReadRequest,
  {
    filePath: '/tmp/root/restored.sql',
    fileExtension: 'sql',
    context: {
      rootToken: 'root-token',
      relativePath: 'nested/restored.sql',
      charset: 'GB18030',
      workspaceTabId: 'restored-sql',
    },
  },
  'restored local files must preserve directory access and encoding context',
);
assert.equal(
  getRestoredLocalFileReadRequest({
    id: 'live-sql',
    type: WorkspaceTabType.LocalSQLFile,
    title: 'live.sql',
    uniqueData: { filePath: '/tmp/live.sql', ddl: 'select 1' },
  }),
  undefined,
  'live local files with content must not be reloaded from disk',
);

console.log('local file workspace tab tests passed');
