import assert from 'node:assert/strict';
import {
  LOCAL_FILE_CHARSETS,
  formatLocalFileEncoding,
  normalizeLocalFileReadResult,
} from './localFileEncoding';

assert.deepEqual(
  normalizeLocalFileReadResult({
    content: 'select \u4e2d\u6587;',
    charset: 'GB18030',
    bom: false,
    path: 'C:\\queries\\sample.sql',
    size: 12,
  }),
  {
    content: 'select \u4e2d\u6587;',
    charset: 'GB18030',
    bom: false,
  },
  'detected encoding metadata should follow the local file into workspace state',
);

assert.equal(formatLocalFileEncoding('UTF-8', true), 'UTF-8 BOM');
assert.equal(formatLocalFileEncoding('GB18030', false), 'GB18030');
assert.equal(formatLocalFileEncoding(), '');
assert.equal(new Set(LOCAL_FILE_CHARSETS).size, LOCAL_FILE_CHARSETS.length, 'encoding choices should be unique');

console.log('local file encoding tests passed');
