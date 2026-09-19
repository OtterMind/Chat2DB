import assert from 'node:assert/strict';
import {
  DEFAULT_MINIMAX_BASE_URL,
  MINIMAX_BASE_URL_PRESETS,
  resolveBaseUrlOnProviderChange,
  resolveProviderBaseUrl,
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

console.log('AI model config default tests passed.');
