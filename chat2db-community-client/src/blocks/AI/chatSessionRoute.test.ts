import assert from 'node:assert/strict';
import { getChatSessionId, getChatSessionUrl, resolveChatSessionVersion } from './chatSessionRoute';

assert.equal(getChatSessionId({ pathname: '/', hash: '#/stream/session-one' }), 'session-one');
assert.equal(getChatSessionId({ pathname: '/stream/session-two', hash: '' }), 'session-two');
assert.equal(getChatSessionUrl({ pathname: '/', hash: '#/stream' }, 'session-one'), '#/stream/session-one');
assert.equal(getChatSessionUrl({ pathname: '/stream', hash: '' }, 'session-two'), '/stream/session-two');
assert.equal(getChatSessionUrl({ pathname: '/', hash: '#/workspace' }, 'session-one'), null);
assert.equal(getChatSessionUrl({ pathname: '/', hash: '#/stream/session-one' }), '#/stream');

async function main() {
  const listedV1 = await resolveChatSessionVersion('legacy', [
    { id: 'legacy', title: 'Legacy', sessionVersion: 1 },
  ], async () => { throw new Error('probe should not be needed'); });
  assert.deepEqual(listedV1, { id: 'legacy', title: 'Legacy', sessionVersion: 1 });

  const listedV2 = await resolveChatSessionVersion('session-two', [
    { id: 'session-two', title: 'Pi', sessionVersion: 2 },
  ], async () => { throw new Error('probe should not be needed'); });
  assert.deepEqual(listedV2, { id: 'session-two', title: 'Pi', sessionVersion: 2 });

  const probedV2 = await resolveChatSessionVersion('session-two', undefined,
    async () => ({ title: 'Recovered Pi' }));
  assert.deepEqual(probedV2, { id: 'session-two', title: 'Recovered Pi', sessionVersion: 2 });

  const fallbackV1 = await resolveChatSessionVersion('legacy', undefined,
    async () => { throw new Error('not an Agent session'); });
  assert.deepEqual(fallbackV1, { id: 'legacy', sessionVersion: 1 });
}

void main();
