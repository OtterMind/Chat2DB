import assert from 'node:assert/strict';
import {
  SelectEditor,
  fromSelectComponentValue,
  getOriginalValueLabel,
  handleSelectEditorKeyDown,
  normalizeMultiSelectComponentValue,
  normalizeSelectEditorOptions,
  resolveSelectEditorPlacement,
  toSelectComponentValue,
} from './index';

const options = [
  { label: 'Pending', value: 'PENDING' },
  { label: '<img src=x onerror=alert(1)>', value: 'DANGEROUS_LABEL' },
];

const normalizedOptions = normalizeSelectEditorOptions(options);
assert.deepEqual(normalizedOptions, options, 'valid labels and values are preserved');
assert.notEqual(normalizedOptions, options, 'option metadata is defensively copied');
assert.equal(
  normalizedOptions[1].label,
  '<img src=x onerror=alert(1)>',
  'database labels remain plain strings for safe React rendering',
);
assert.deepEqual(normalizeSelectEditorOptions([null as any]), [], 'invalid options are rejected');

assert.equal(getOriginalValueLabel(null), '<null>');
assert.equal(getOriginalValueLabel('CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_DEFAULT'), '<default>');
assert.equal(getOriginalValueLabel('CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_GENERATED'), '<generated>');

assert.equal(toSelectComponentValue(null, false), undefined, 'a null ENUM value uses the null placeholder');
assert.equal(toSelectComponentValue('PENDING', false), 'PENDING');
assert.equal(fromSelectComponentValue('PENDING', false), 'PENDING');
assert.deepEqual(toSelectComponentValue('ALPHA,GAMMA', true), ['ALPHA', 'GAMMA']);
assert.deepEqual(
  toSelectComponentValue('CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_DEFAULT', true),
  [],
  'the create-row default sentinel is not rendered as a SET tag',
);
assert.equal(fromSelectComponentValue(['ALPHA', 'GAMMA'], true), 'ALPHA,GAMMA');
assert.equal(fromSelectComponentValue([], true), '', 'clearing every member produces the MySQL empty SET value');
assert.deepEqual(
  normalizeMultiSelectComponentValue(['ALPHA', 'GAMMA', 'BETA'], [
    { label: 'Alpha', value: 'ALPHA' },
    { label: 'Beta', value: 'BETA' },
    { label: 'Gamma', value: 'GAMMA' },
  ]),
  ['ALPHA', 'BETA', 'GAMMA'],
  'SET members follow metadata order rather than click order',
);
assert.deepEqual(
  normalizeMultiSelectComponentValue(['UNKNOWN', 'ALPHA'], [{ label: 'Alpha', value: 'ALPHA' }]),
  ['ALPHA', 'UNKNOWN'],
  'an unknown stored member is not silently dropped when another member changes',
);

let stoppedKeyDownCount = 0;
let cancelCount = 0;
handleSelectEditorKeyDown({ key: 'Enter', stopPropagation: () => stoppedKeyDownCount++ }, () => cancelCount++);
handleSelectEditorKeyDown({ key: 'Escape', stopPropagation: () => stoppedKeyDownCount++ }, () => cancelCount++);
handleSelectEditorKeyDown({ key: 'ArrowDown', stopPropagation: () => stoppedKeyDownCount++ }, () => cancelCount++);
assert.equal(stoppedKeyDownCount, 2, 'Enter and Escape do not bubble into the VTable editor manager');
assert.equal(cancelCount, 1, 'Escape cancels the active VTable value exactly once');

assert.equal(resolveSelectEditorPlacement({ top: 10, left: 20, width: 120, height: 30 }, 300, 3), 'bottomLeft');
assert.equal(resolveSelectEditorPlacement({ top: 260, left: 20, width: 120, height: 30 }, 300, 3), 'topLeft');

const editor = new SelectEditor(options, {});
assert.equal(editor.multiple, false, 'ENUM uses the shared Select component in single-select mode');
editor.setValue(null);
assert.equal(editor.getValue(), null, 'an untouched null remains null');
editor.handleSelectionChange('PENDING');
assert.equal(editor.getValue(), 'PENDING', 'a component selection updates the editor value');
editor.cancelEditing();
assert.equal(editor.getValue(), null, 'cancel restores the original value before the VTable edit ends');
editor.setValue('NOT_IN_METADATA');
assert.equal(editor.getValue(), 'NOT_IN_METADATA', 'an untouched unknown value remains unchanged');

console.log('SelectEditor component adapter tests passed');
