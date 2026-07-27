import assert from 'node:assert/strict';
import {
  appendCustomModelEntryOption,
  CUSTOM_MODEL_ENTRY_OPTION_VALUE,
  isCustomModelEntryOption,
} from './modelSelectOptions';

const emptyOptions = appendCustomModelEntryOption([], 'Custom model');
assert.deepEqual(emptyOptions, [
  {
    label: 'Custom model',
    value: CUSTOM_MODEL_ENTRY_OPTION_VALUE,
  },
]);

const configuredOptions = [{ label: 'GPT-4o', value: 'gpt-4o' }];
assert.deepEqual(appendCustomModelEntryOption(configuredOptions, 'Custom model', 'custom-option'), [
  configuredOptions[0],
  {
    className: 'custom-option',
    label: 'Custom model',
    value: CUSTOM_MODEL_ENTRY_OPTION_VALUE,
  },
]);

assert.deepEqual(appendCustomModelEntryOption(configuredOptions, null, 'custom-option'), configuredOptions);
assert.equal(isCustomModelEntryOption(CUSTOM_MODEL_ENTRY_OPTION_VALUE), true);
assert.equal(isCustomModelEntryOption('gpt-4o'), false);

console.log('AI model select option tests passed.');
