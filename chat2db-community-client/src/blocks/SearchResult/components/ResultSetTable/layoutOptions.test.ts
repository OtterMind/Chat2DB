import assert from 'node:assert/strict';
import test from 'node:test';
import { RESULT_TABLE_CONTENT_LAYOUT_OPTIONS } from './layoutOptions';

test('result tables measure multiline cell content instead of clipping it', () => {
  assert.deepEqual(RESULT_TABLE_CONTENT_LAYOUT_OPTIONS, {
    autoWrapText: true,
    heightMode: 'autoHeight',
    maxCharactersNumber: Number.MAX_SAFE_INTEGER,
  });
});
