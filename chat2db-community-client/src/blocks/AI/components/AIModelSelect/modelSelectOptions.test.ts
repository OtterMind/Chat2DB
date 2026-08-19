import assert from 'node:assert/strict';
import {
  appendCustomModelEntryOption,
  CUSTOM_MODEL_ENTRY_OPTION_VALUE,
  isCustomModelEntryOption,
  isModelOptionAvailable,
  resolveSelectedModel,
  shouldOpenCustomModelDirectly,
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

const staleSelection = { label: 'GPT-5.2', value: 'preset:OPENAI:gpt-5.2' };
assert.equal(isModelOptionAvailable([], staleSelection), false);
assert.equal(isModelOptionAvailable(configuredOptions, configuredOptions[0]), true);
assert.equal(resolveSelectedModel([], staleSelection), null);
assert.equal(shouldOpenCustomModelDirectly([], true), true);
assert.equal(shouldOpenCustomModelDirectly(configuredOptions, true), false);
assert.equal(shouldOpenCustomModelDirectly(undefined, true), false);
assert.equal(shouldOpenCustomModelDirectly([], false), false);

const availableOptions = [
  { label: 'Custom Claude', value: 'config:claude', isDefault: false },
  { label: 'Custom OpenAI', value: 'config:openai', isDefault: true },
];
assert.deepEqual(resolveSelectedModel(availableOptions, staleSelection), {
  label: 'Custom OpenAI',
  value: 'config:openai',
});
assert.deepEqual(resolveSelectedModel(availableOptions, { label: 'Old label', value: 'config:claude' }), {
  label: 'Custom Claude',
  value: 'config:claude',
});

console.log('AI model select option tests passed.');
