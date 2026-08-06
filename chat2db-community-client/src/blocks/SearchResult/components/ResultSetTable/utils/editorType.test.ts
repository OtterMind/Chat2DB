import assert from 'node:assert/strict';
import { SelectEditor } from '@/blocks/CanvasTable/editor/SelectIEditor';
import { MultiSelectEditor } from '@/blocks/CanvasTable/editor/MultiSelectIEditor';
import { resolveResultSetEditor } from './editorType';

assert.equal(resolveResultSetEditor('DATE'), 'custom-date-editor', 'DATE maps to date editor');
assert.equal(resolveResultSetEditor('TIME'), 'custom-time-editor', 'TIME maps to time editor');
assert.equal(resolveResultSetEditor('DATETIME'), 'custom-datetime-editor', 'DATETIME maps to datetime editor');
assert.equal(resolveResultSetEditor('TIMESTAMP'), 'custom-timestamp-editor', 'TIMESTAMP maps to timestamp editor');
assert.equal(resolveResultSetEditor('TEXT'), 'custom-input-editor', 'TEXT falls back to text editor');
assert.equal(resolveResultSetEditor(undefined), 'custom-input-editor', 'missing editor type falls back to text editor');
assert.equal(
  resolveResultSetEditor('UNSUPPORTED'),
  'custom-input-editor',
  'unknown editor type falls back to text editor',
);
assert.equal(
  resolveResultSetEditor('SELECT'),
  'custom-input-editor',
  'SELECT without options falls back to text editor',
);
assert.equal(
  resolveResultSetEditor('SELECT', []),
  'custom-input-editor',
  'SELECT with empty options falls back to text editor',
);
assert.equal(
  resolveResultSetEditor('MULTI_SELECT'),
  'custom-input-editor',
  'MULTI_SELECT without options falls back to text editor',
);

const options = [
  { label: 'Pending', value: 'PENDING' },
  { label: '<img src=x onerror=alert(1)>', value: 'unsafe-looking-value' },
];
const firstSelectEditor = resolveResultSetEditor('SELECT', options, {
  colorBgContainer: '#fff',
  colorText: '#111',
});
const secondSelectEditor = resolveResultSetEditor('SELECT', options, {
  colorBgContainer: '#000',
  colorText: '#eee',
});

assert.ok(firstSelectEditor instanceof SelectEditor, 'SELECT with options maps to a select editor');
assert.ok(secondSelectEditor instanceof SelectEditor, 'each SELECT column receives a select editor');
assert.notEqual(firstSelectEditor, secondSelectEditor, 'SELECT editor instances are isolated per column');
assert.deepEqual(firstSelectEditor.options, options, 'SELECT editor preserves option labels and values');
assert.notEqual(firstSelectEditor.options, options, 'SELECT editor defensively copies option metadata');
firstSelectEditor.setValue(null);
assert.equal(firstSelectEditor.getValue(), null, 'an unmodified null value remains null');
firstSelectEditor.setValue('NOT_IN_METADATA');
assert.equal(firstSelectEditor.getValue(), 'NOT_IN_METADATA', 'an unmodified unknown value remains unchanged');

const multiSelectEditor = resolveResultSetEditor('MULTI_SELECT', options, {
  colorBgContainer: '#fff',
  colorText: '#111',
});
assert.ok(multiSelectEditor instanceof MultiSelectEditor, 'MULTI_SELECT with options maps to a multi-select editor');
assert.deepEqual(multiSelectEditor.options, options, 'MULTI_SELECT editor preserves option labels and values');
assert.notEqual(multiSelectEditor.options, options, 'MULTI_SELECT editor defensively copies option metadata');
assert.equal(
  resolveResultSetEditor('MULTI_SELECT', [{ label: 'Comma', value: 'one,two' }]),
  'custom-input-editor',
  'MULTI_SELECT falls back when an option cannot be represented by comma-separated values',
);
assert.equal(
  resolveResultSetEditor('MULTI_SELECT', [{ label: 'Empty member', value: '' }]),
  'custom-input-editor',
  'MULTI_SELECT falls back when an empty member is indistinguishable from an empty selection',
);

assert.equal(
  resolveResultSetEditor('SELECT', [null as any]),
  'custom-input-editor',
  'SELECT falls back when runtime metadata contains no valid options after normalization',
);

console.log('ResultSetTable editorType tests passed');
