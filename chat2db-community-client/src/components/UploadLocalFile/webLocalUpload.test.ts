import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { exceedsSingleFileSizeLimit, mergeFileSelections } from './selectionLimits';

const source = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
const styleSource = fs.readFileSync(path.join(__dirname, 'style.ts'), 'utf8');

test('web local uploads use the browser upload control instead of JCEF', () => {
  assert.match(source, /const isWebLocalUpload = !isDesktop && !isWebOssUpload/);
  assert.match(source, /if \(isWebLocalUpload\) \{\s+return false;/);
  assert.match(source, /\{!isDesktop \? \(\s+<Upload\.Dragger/);
  assert.match(source, /\) : \(\s+<button[\s\S]*?type="button"[\s\S]*?onClick=\{handleUpdate\}\s*>/);
});

test('multi-file selection is forwarded to both browser and desktop selectors', () => {
  assert.match(source, /jcefApi\s*\.selectFile\(\{ fileTypeList, fileSize, multiple \}\)/);
  assert.match(source, /\{multiple &&\s+\(isDesktop \?/);
  assert.match(source, /<Upload[\s\S]*?accept=\{accept\}[\s\S]*?multiple/);
});

test('file actions remain keyboard-accessible and long names cannot cover them', () => {
  assert.match(source, /<Button[\s\S]*?aria-label=\{`\$\{i18n\('common\.button\.delete'\)\}/);
  assert.match(source, /commitFileList\(fileListRef\.current\.filter/);
  assert.match(styleSource, /text-overflow: ellipsis/);
  assert.match(styleSource, /min-width: 0/);
  assert.doesNotMatch(styleSource, /display: none/);
});

test('single-file size validation allows the exact configured boundary', () => {
  const oneMegabyte = 1024 * 1024;

  assert.equal(exceedsSingleFileSizeLimit(oneMegabyte, 1), false);
  assert.equal(exceedsSingleFileSizeLimit(oneMegabyte + 1, 1), true);
  assert.doesNotMatch(source, /setFileList\(\[\]\)/);
});

test('multi-file batches accept the count boundary and reject only the overflow', () => {
  const current = [{ fileName: 'one.csv', fileSize: 1 }];
  const result = mergeFileSelections(
    current,
    [
      { fileName: 'two.csv', fileSize: 1 },
      { fileName: 'three.csv', fileSize: 1 },
      { fileName: 'four.csv', fileSize: 1 },
    ],
    { multiple: true, maxFiles: 3 },
  );

  assert.deepEqual(
    result.fileList.map((file) => file.fileName),
    ['one.csv', 'two.csv', 'three.csv'],
  );
  assert.deepEqual(
    result.rejected.map(({ file, violation }) => [file.fileName, violation]),
    [['four.csv', 'maxFiles']],
  );
  assert.deepEqual(result.violations, ['maxFiles']);
});

test('known total sizes allow the exact byte boundary and reject additions above it', () => {
  const atBoundary = mergeFileSelections(
    [{ fileName: 'one.csv', fileSize: 4 }],
    [{ fileName: 'two.csv', file: { size: 6 } }],
    { multiple: true, maxTotalSizeBytes: 10 },
  );
  const aboveBoundary = mergeFileSelections(atBoundary.fileList, [{ fileName: 'three.csv', fileSize: 1 }], {
    multiple: true,
    maxTotalSizeBytes: 10,
  });

  assert.equal(atBoundary.accepted.length, 1);
  assert.equal(atBoundary.fileList.length, 2);
  assert.deepEqual(aboveBoundary.fileList, atBoundary.fileList);
  assert.deepEqual(aboveBoundary.violations, ['maxTotalSizeBytes']);
});

test('single-file selection replaces the current file without counting its size', () => {
  const current = [{ fileName: 'old.csv', fileSize: 100 }];
  const replacement = { fileName: 'new.csv', fileSize: 10 };
  const accepted = mergeFileSelections(current, [replacement], {
    multiple: false,
    maxFiles: 1,
    maxTotalSizeBytes: 10,
  });
  const rejected = mergeFileSelections(current, [{ fileName: 'too-large.csv', fileSize: 11 }], {
    multiple: false,
    maxTotalSizeBytes: 10,
  });

  assert.deepEqual(accepted.fileList, [replacement]);
  assert.deepEqual(rejected.fileList, current);
  assert.deepEqual(rejected.violations, ['maxTotalSizeBytes']);
});

test('files without a known size remain selectable and do not hide known-size overflow', () => {
  const unknownSize = { fileName: 'desktop-unknown.csv' };
  const atBoundary = mergeFileSelections(
    [],
    [unknownSize, { fileName: 'known.csv', fileSize: 10 }],
    { multiple: true, maxTotalSizeBytes: 10 },
  );
  const aboveBoundary = mergeFileSelections(atBoundary.fileList, [{ fileName: 'overflow.csv', fileSize: 1 }], {
    multiple: true,
    maxTotalSizeBytes: 10,
  });

  assert.deepEqual(atBoundary.fileList, [unknownSize, { fileName: 'known.csv', fileSize: 10 }]);
  assert.deepEqual(aboveBoundary.fileList, atBoundary.fileList);
  assert.deepEqual(aboveBoundary.violations, ['maxTotalSizeBytes']);
});
