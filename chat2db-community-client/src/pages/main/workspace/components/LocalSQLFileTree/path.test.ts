import assert from 'node:assert/strict';
import { createLocalFileTreePathOperations } from './path';

const posix = createLocalFileTreePathOperations(false);
assert.notEqual(posix.getComparablePath('/tmp/a\\b.sql'), posix.getComparablePath('/tmp/a/b.sql'));
assert.equal(posix.isSameOrChildPath('/tmp/sql/nested/query.sql', '/tmp/sql', 'directory'), true);
assert.equal(posix.isSameOrChildPath('/tmp/sql-copy/query.sql', '/tmp/sql', 'directory'), false);
assert.equal(posix.replacePathPrefix('/tmp/sql/query.sql', '/tmp/sql', '/tmp/renamed'), '/tmp/renamed/query.sql');
assert.equal(posix.replacePathPrefix('sql/query.sql', 'sql', 'renamed'), 'renamed/query.sql');
assert.deepEqual(
  posix.getRenamedFilePaths({
    filePath: '/tmp/sql/nested/query.sql',
    fileRelativePath: 'sql/nested/query.sql',
    sourcePath: '/tmp/sql',
    sourceRelativePath: 'sql',
    targetPath: '/tmp/renamed',
    targetRelativePath: 'renamed',
  }),
  {
    filePath: '/tmp/renamed/nested/query.sql',
    fileRelativePath: 'renamed/nested/query.sql',
  },
);
assert.equal(posix.getParentPath('/tmp/a\\b.sql'), '/tmp');

const windows = createLocalFileTreePathOperations(true);
assert.equal(windows.getComparablePath('C:\\SQL\\Query.sql'), windows.getComparablePath('c:/sql/query.sql'));
assert.equal(windows.isSameOrChildPath('C:\\SQL\\nested\\query.sql', 'c:/sql', 'directory'), true);
assert.equal(
  windows.replacePathPrefix('C:\\SQL\\nested\\query.sql', 'c:\\sql', 'C:\\Renamed'),
  'C:\\Renamed\\nested\\query.sql',
);
assert.equal(windows.replacePathPrefix('sql\\query.sql', 'SQL', 'renamed'), 'renamed/query.sql');
assert.equal(windows.getParentPath('C:\\SQL\\query.sql'), 'C:\\SQL');

console.log('local file tree path tests passed');
