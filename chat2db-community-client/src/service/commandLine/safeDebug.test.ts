import assert from 'node:assert/strict';
import { createSafeIpcDebugMetadata } from './safeDebug';

const canary = 'CANARY_PROMPT_SQL_ATTACHMENT_TOOL_RESULT';
const metadata = createSafeIpcDebugMetadata('request-1', 'post', '/api/chat', {
  prompt: canary,
  sql: canary,
  attachment: canary,
  toolResult: canary,
});

assert.equal(metadata.requestId, 'request-1');
assert.equal(metadata.method, 'post');
assert.equal(metadata.route, '/api/chat');
assert.ok(metadata.payloadSize > 0);
assert.equal(JSON.stringify(metadata).includes(canary), false);

console.log('Safe IPC debug metadata tests passed');

