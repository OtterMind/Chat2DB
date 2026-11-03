import assert from 'node:assert/strict';
import {
  DEFAULT_MINIMAX_BASE_URL,
  MINIMAX_BASE_URL_PRESETS,
  MINIMAX_MODEL_PRESETS,
  resolveBaseUrlOnProviderChange,
  resolveProviderBaseUrl,
  resolveProviderModel,
} from './modelConfigDefaults';

assert.deepEqual(
  MINIMAX_BASE_URL_PRESETS.map(({ value }) => value),
  [
    'https://api.minimax.io/v1',
    'https://api.minimaxi.com/v1',
    'https://api.minimax.io/anthropic',
    'https://api.minimaxi.com/anthropic',
  ],
);
assert.equal(DEFAULT_MINIMAX_BASE_URL, 'https://api.minimax.io/v1');

assert.equal(resolveProviderBaseUrl('MINIMAX', ''), DEFAULT_MINIMAX_BASE_URL);
assert.equal(resolveProviderBaseUrl('MINIMAX', '   '), DEFAULT_MINIMAX_BASE_URL);
assert.equal(resolveProviderBaseUrl('MINIMAX', 'https://api.minimax.chat/v1'), 'https://api.minimax.chat/v1');
assert.equal(resolveProviderBaseUrl('OPENAI', ''), '');

assert.equal(resolveBaseUrlOnProviderChange('MINIMAX', ''), DEFAULT_MINIMAX_BASE_URL);
MINIMAX_BASE_URL_PRESETS.forEach(({ value }) => {
  assert.equal(resolveBaseUrlOnProviderChange('OPENAI', value), '');
});
assert.equal(
  resolveBaseUrlOnProviderChange('OPENAI', 'https://proxy.example.com/v1'),
  'https://proxy.example.com/v1',
);

assert.deepEqual(MINIMAX_MODEL_PRESETS.map(({ value }) => value), ['MiniMax-M3.1-Flash', 'MiniMax-M3']);
assert.equal(resolveProviderModel('MINIMAX', ''), 'MiniMax-M3.1-Flash');
assert.equal(resolveProviderModel('MINIMAX', '   '), 'MiniMax-M3.1-Flash');
['MiniMax-M3', 'MiniMax-M2.7', 'custom-model'].forEach((model) => {
  assert.equal(resolveProviderModel('MINIMAX', model), model);
});
assert.equal(resolveProviderModel('OPENAI', ''), '');
assert.equal(resolveProviderModel('CLAUDE', 'custom-model'), 'custom-model');

console.log('AI model config default tests passed.');
