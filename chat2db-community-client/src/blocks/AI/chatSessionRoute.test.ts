import assert from 'node:assert/strict';
import { getChatSessionId, getChatSessionUrl } from './chatSessionRoute';

assert.equal(getChatSessionId({ pathname: '/', hash: '#/stream/session-one' }), 'session-one');
assert.equal(getChatSessionId({ pathname: '/stream/session-two', hash: '' }), 'session-two');
assert.equal(getChatSessionUrl({ pathname: '/', hash: '#/stream' }, 'session-one'), '#/stream/session-one');
assert.equal(getChatSessionUrl({ pathname: '/stream', hash: '' }, 'session-two'), '/stream/session-two');
assert.equal(getChatSessionUrl({ pathname: '/', hash: '#/workspace' }, 'session-one'), null);
assert.equal(getChatSessionUrl({ pathname: '/', hash: '#/stream/session-one' }), '#/stream');
