import assert from 'node:assert/strict';
import { resolveBaseUrlOnProviderChange, resolveProviderBaseUrl } from './modelConfigDefaults';

assert.equal(resolveProviderBaseUrl('OPENAI', ''), '');
assert.equal(resolveProviderBaseUrl('CLAUDE', 'https://api.anthropic.com'), 'https://api.anthropic.com');

assert.equal(
  resolveBaseUrlOnProviderChange('OPENAI', 'https://proxy.example.com/v1'),
  'https://proxy.example.com/v1',
);
assert.equal(resolveBaseUrlOnProviderChange('CLAUDE', 'https://anthropic.example.com'), 'https://anthropic.example.com');

console.log('AI model config default tests passed.');
