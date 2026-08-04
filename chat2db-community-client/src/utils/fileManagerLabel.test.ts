import assert from 'node:assert/strict';

import { getFileManagerLabelKey, resolveFileManagerI18nName } from './fileManagerLabel';

const macUserAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)';
const windowsUserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)';
const linuxUserAgent = 'Mozilla/5.0 (X11; Linux x86_64)';

assert.equal(resolveFileManagerI18nName(macUserAgent), 'Finder');
assert.equal(resolveFileManagerI18nName(windowsUserAgent), 'FileExplorer');
assert.equal(resolveFileManagerI18nName(linuxUserAgent), 'FileManager');

assert.equal(
  getFileManagerLabelKey('workspace', windowsUserAgent),
  'workspace.localSqlFileTree.revealInFileExplorer',
);
assert.equal(
  getFileManagerLabelKey('shortcut', windowsUserAgent),
  'setting.shortcut.localSqlFileTreeRevealInFileExplorer',
);
assert.equal(getFileManagerLabelKey('workspace', macUserAgent), 'workspace.localSqlFileTree.revealInFinder');
assert.equal(
  getFileManagerLabelKey('shortcut', linuxUserAgent),
  'setting.shortcut.localSqlFileTreeRevealInFileManager',
);

console.log('File manager label tests passed.');
