import assert from 'node:assert/strict';
import test from 'node:test';
import { getDownloadFilename } from './downloadFilename';

test('decodes RFC 5987 download filenames', () => {
  assert.equal(
    getDownloadFilename("attachment; filename*=UTF-8''%E7%9F%A5%E8%AF%86%E6%95%B0%E6%8D%AE.xlsx"),
    '知识数据.xlsx',
  );
});

test('supports quoted download filenames and a safe fallback', () => {
  assert.equal(getDownloadFilename('attachment; filename="knowledge.xlsx"'), 'knowledge.xlsx');
  assert.equal(getDownloadFilename(null), 'download');
});
