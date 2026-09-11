import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';

const source = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');

test('web local uploads use the browser upload control instead of JCEF', () => {
  assert.match(source, /const isWebLocalUpload = !isDesktop && !isWebOssUpload/);
  assert.match(source, /if \(isWebLocalUpload\) \{\s+return false;/);
  assert.match(source, /\{!isDesktop \? \(\s+<Upload\.Dragger/);
  assert.match(source, /\) : \(\s+<div className=\{styles\.uploadDragger\} onClick=\{handleUpdate\}>/);
});
