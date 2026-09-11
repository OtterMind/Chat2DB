import assert from 'node:assert/strict';
import { createSingleFileMonacoEditorRef } from './refAdapter';

let value = 'legacy desc';
const receivedRanges: unknown[] = [];
const editor = {
  getAllContent: () => value,
  setValue: (text: unknown, range: unknown = 'end') => {
    receivedRanges.push(range);
    value = range === 'cover' ? String(text) : `${value}${text}`;
  },
};

const editorRef = createSingleFileMonacoEditorRef(() => editor, () => undefined);

editorRef.setValue?.('id desc', 'cover');
assert.equal(value, 'id desc', 'the first grid sort replaces stale order text');

editorRef.setValue?.('category asc', 'cover');
assert.equal(value, 'category asc', 'switching grid columns replaces the previous sort instead of appending');

editorRef.setValue?.('', 'cover');
assert.equal(value, '', 'clearing grid sorting removes the previous order text');
assert.deepEqual(receivedRanges, ['cover', 'cover', 'cover'], 'the adapter preserves the Monaco edit range');

console.log('SingleFileMonacoEditor ref adapter tests passed');
