import assert from 'node:assert/strict';
import { mergeLatestLocalFileBoundInfo } from './liveEditorBoundInfo';

const merged = mergeLatestLocalFileBoundInfo(
  {
    workspaceTabId: 'file-tab',
    filePath: '/old/query.sql',
    fileRelativePath: 'old/query.sql',
    fileCharset: 'UTF-8',
    ddl: 'old persisted content',
  },
  { ddl: 'latest saved content' },
  {
    workspaceTabId: 'file-tab',
    filePath: '/renamed/query.sql',
    fileRelativePath: 'renamed/query.sql',
    fileCharset: 'UTF-16LE',
    fileBom: true,
    ddl: 'previous saved content',
  },
);

assert.deepEqual(merged, {
  workspaceTabId: 'file-tab',
  filePath: '/renamed/query.sql',
  fileRelativePath: 'renamed/query.sql',
  fileCharset: 'UTF-16LE',
  fileBom: true,
  fileExtension: undefined,
  filePreviewUrl: undefined,
  filePreviewMimeType: undefined,
  fileRootToken: undefined,
  ddl: 'latest saved content',
});

console.log('live editor bound info tests passed');
