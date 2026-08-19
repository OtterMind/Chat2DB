import assert from 'node:assert/strict';
import { applyConnectionIdentityColorUpdate } from './identityColorUpdate';

async function main() {
  const unchangedConnection = { id: 1, alias: 'unchanged', identityColor: '#AABBCC' };
  let requestCount = 0;
  const unchangedResult = await applyConnectionIdentityColorUpdate(
    unchangedConnection,
    '#aabbcc',
    ' #AABBCC ',
    async () => {
      requestCount += 1;
      throw new Error('unchanged colors must not be persisted');
    },
  );
  assert.equal(unchangedResult, unchangedConnection);
  assert.equal(requestCount, 0);

  let selectedRequest: any;
  const selectedResult = await applyConnectionIdentityColorUpdate(
    { id: 2, alias: 'selected', identityColor: null },
    null,
    ' #12ab34 ',
    async (request) => {
      selectedRequest = request;
      return {
        id: request.id,
        identityColor: request.identityColor,
        environmentId: 7,
        environment: null,
      };
    },
  );
  assert.deepEqual(selectedRequest, { id: 2, identityColor: '#12AB34' });
  assert.equal(selectedResult.alias, 'selected');
  assert.equal(selectedResult.identityColor, '#12AB34');

  let clearRequest: any;
  const clearedResult = await applyConnectionIdentityColorUpdate(
    { id: 3, identityColor: '#112233' },
    '#112233',
    null,
    async (request) => {
      clearRequest = request;
      return {
        id: request.id,
        identityColor: null,
        environmentId: null,
        environment: null,
      };
    },
  );
  assert.deepEqual(clearRequest, { id: 3, identityColor: null });
  assert.equal(clearedResult.identityColor, null);

  console.log('Connection edit identity color update tests passed');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
