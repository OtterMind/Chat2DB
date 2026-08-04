import assert from 'node:assert/strict';
import {
  DEFAULT_MINIMAX_BASE_URL,
  resolveBaseUrlOnProviderChange,
  resolveProviderBaseUrl,
} from './modelConfigDefaults';

assert.equal(resolveProviderBaseUrl('MINIMAX', ''), DEFAULT_MINIMAX_BASE_URL);
assert.equal(resolveProviderBaseUrl('MINIMAX', '   '), DEFAULT_MINIMAX_BASE_URL);
assert.equal(resolveProviderBaseUrl('MINIMAX', 'https://api.minimax.chat/v1'), 'https://api.minimax.chat/v1');
assert.equal(resolveProviderBaseUrl('OPENAI', ''), '');

assert.equal(resolveBaseUrlOnProviderChange('MINIMAX', ''), DEFAULT_MINIMAX_BASE_URL);
assert.equal(resolveBaseUrlOnProviderChange('OPENAI', DEFAULT_MINIMAX_BASE_URL), '');
assert.equal(
  resolveBaseUrlOnProviderChange('OPENAI', 'https://proxy.example.com/v1'),
  'https://proxy.example.com/v1',
);

console.log('AI model config default tests passed.');
