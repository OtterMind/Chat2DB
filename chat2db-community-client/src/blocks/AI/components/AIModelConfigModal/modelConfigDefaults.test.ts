import assert from 'node:assert/strict';
import {
  DEFAULT_MINIMAX_BASE_URL,
  resolveBaseUrlOnProviderChange,
  resolveProviderBaseUrl,
  resolveAgentModelApi,
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

assert.equal(resolveAgentModelApi('OPENAI'), 'openai-responses');
assert.equal(resolveAgentModelApi('OPENAI', 'openai-completions'), 'openai-completions');
assert.equal(resolveAgentModelApi('CLAUDE'), 'anthropic-messages');
assert.equal(resolveAgentModelApi('GEMINI'), 'google-generative-ai');
assert.equal(resolveAgentModelApi('MINIMAX'), 'openai-completions');
assert.equal(resolveAgentModelApi('MINIMAX', undefined, 'https://api.minimax.io/anthropic'), 'anthropic-messages');

console.log('AI model config default tests passed.');
