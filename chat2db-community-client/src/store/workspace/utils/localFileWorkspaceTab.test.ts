import assert from 'node:assert/strict';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IBoundInfo, IWorkspaceTab } from '@/typings/workspace';
import { refreshLocalFileWorkspaceTab } from './localFileWorkspaceTab';

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

console.log('local file workspace tab tests passed');
