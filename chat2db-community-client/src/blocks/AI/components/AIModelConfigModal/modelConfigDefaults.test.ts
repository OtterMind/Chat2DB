import assert from 'node:assert/strict';
import {
  DEFAULT_LITELLM_BASE_URL,
  DEFAULT_MINIMAX_BASE_URL,
  resolveBaseUrlOnProviderChange,
  resolveProviderBaseUrl,
} from './modelConfigDefaults';

assert.equal(resolveProviderBaseUrl('MINIMAX', ''), DEFAULT_MINIMAX_BASE_URL);
assert.equal(resolveProviderBaseUrl('MINIMAX', '   '), DEFAULT_MINIMAX_BASE_URL);
assert.equal(resolveProviderBaseUrl('MINIMAX', 'https://api.minimax.chat/v1'), 'https://api.minimax.chat/v1');
assert.equal(resolveProviderBaseUrl('OPENAI', ''), '');

// LiteLLM: self-hosted proxy default, overridable by the user.
assert.equal(resolveProviderBaseUrl('LITELLM', ''), DEFAULT_LITELLM_BASE_URL);
assert.equal(resolveProviderBaseUrl('LITELLM', '   '), DEFAULT_LITELLM_BASE_URL);
assert.equal(resolveProviderBaseUrl('LITELLM', 'http://litellm.internal:8000/v1'), 'http://litellm.internal:8000/v1');

assert.equal(resolveBaseUrlOnProviderChange('MINIMAX', ''), DEFAULT_MINIMAX_BASE_URL);
assert.equal(resolveBaseUrlOnProviderChange('LITELLM', ''), DEFAULT_LITELLM_BASE_URL);
assert.equal(resolveBaseUrlOnProviderChange('OPENAI', DEFAULT_MINIMAX_BASE_URL), '');
// Switching away from LiteLLM clears its auto-filled default too.
assert.equal(resolveBaseUrlOnProviderChange('OPENAI', DEFAULT_LITELLM_BASE_URL), '');
assert.equal(
  resolveBaseUrlOnProviderChange('OPENAI', 'https://proxy.example.com/v1'),
  'https://proxy.example.com/v1',
);

console.log('AI model config default tests passed.');
