import assert from 'node:assert/strict';
import { MultiSelectEditor } from './index';

const options = [
  { label: 'Alpha', value: 'ALPHA' },
  { label: '<img src=x onerror=alert(1)>', value: 'BETA' },
  { label: 'Gamma', value: 'GAMMA' },
];

const editor = new MultiSelectEditor(options, {});
assert.equal(editor.multiple, true, 'SET uses the shared Select component in multiple mode');
assert.deepEqual(editor.options, options, 'the component receives the ordered MySQL SET members');
assert.notEqual(editor.options, options, 'option metadata is defensively copied');

editor.setValue('ALPHA,GAMMA');
assert.equal(editor.getValue(), 'ALPHA,GAMMA', 'an untouched SET value is preserved');
editor.handleSelectionChange(['ALPHA', 'GAMMA', 'BETA']);
assert.equal(editor.getValue(), 'ALPHA,BETA,GAMMA', 'selected members are serialized in metadata order');
editor.setValue('UNKNOWN');
editor.handleSelectionChange(['UNKNOWN', 'ALPHA']);
assert.equal(editor.getValue(), 'ALPHA,UNKNOWN', 'changing a known member does not silently drop an unknown stored member');
editor.cancelEditing();
assert.equal(editor.getValue(), 'UNKNOWN', 'Escape restores the complete original SET value');
editor.handleSelectionChange([]);
assert.equal(editor.getValue(), '', 'clearing every member produces the MySQL empty SET value');

editor.setValue(null);
assert.equal(editor.getValue(), null, 'an untouched null remains null');
editor.setValue('UNKNOWN');
assert.equal(editor.getValue(), 'UNKNOWN', 'an untouched unknown value remains unchanged');

console.log('MultiSelectEditor component adapter tests passed');
