import assert from 'node:assert/strict';
import { secretImportRequest } from './secretImportTransport';

let capturedRequest = '';
Object.defineProperty(globalThis, 'window', {
  configurable: true,
  value: {
    javaQuery: ({ request, onSuccess }) => {
      capturedRequest = request;
      const parsed = JSON.parse(request);
      onSuccess(
        JSON.stringify({
          uuid: parsed.uuid,
          message: { success: true, data: { status: 'SUCCEEDED', itemId: 'item-1' } },
        }),
      );
    },
  },
});

async function main() {
  const result = await secretImportRequest<{ status: string; itemId: string }>(
    '/api/ai/secret-import/item',
    { ciphertextBase64: 'opaque-ciphertext' },
  );
  assert.equal(result.status, 'SUCCEEDED');
  assert.equal(result.itemId, 'item-1');
  assert.equal(JSON.parse(capturedRequest).requestUrl, '/api/ai/secret-import/item');

  // A mismatched callback cannot be associated with another request.
  window.javaQuery = ({ onSuccess }) =>
    onSuccess(JSON.stringify({ uuid: 'wrong', message: { success: true, data: {} } }));
  await assert.rejects(
    secretImportRequest('/api/ai/secret-import/item', { ciphertextBase64: 'opaque' }),
    /SECRET_IMPORT_INVALID_RESPONSE/,
  );

  console.log('Secret import dedicated transport tests passed.');
}

void main();
