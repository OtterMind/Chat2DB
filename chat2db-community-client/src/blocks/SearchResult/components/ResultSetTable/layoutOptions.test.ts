import assert from 'node:assert/strict';
import test from 'node:test';
import { RESULT_TABLE_CONTENT_LAYOUT_OPTIONS } from './layoutOptions';

test('result tables keep fixed rows while allowing wrapped content on demand', () => {
  assert.deepEqual(RESULT_TABLE_CONTENT_LAYOUT_OPTIONS, {
    autoWrapText: true,
    heightMode: 'standard',
    maxCharactersNumber: Number.MAX_SAFE_INTEGER,
  });
});
