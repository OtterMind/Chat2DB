import assert from 'node:assert/strict';
import { normalizeLocalFileReadResult } from './localFileEncoding';

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

console.log('local file encoding tests passed');
