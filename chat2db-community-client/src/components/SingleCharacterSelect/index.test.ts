import assert from 'node:assert/strict';
import { buildSingleCharacterOptions } from './index';

const presets = [
  { value: ',', label: 'Comma' },
  { value: ';', label: 'Semicolon' },
];

assert.equal(buildSingleCharacterOptions(',', presets, true, (value) => `Custom (${value})`), presets);
assert.deepEqual(buildSingleCharacterOptions('^', presets, true, (value) => `Custom (${value})`), [
  ...presets,
  { value: '^', label: 'Custom (^)' },
]);
assert.equal(buildSingleCharacterOptions('^', presets, false, (value) => `Custom (${value})`), presets);
