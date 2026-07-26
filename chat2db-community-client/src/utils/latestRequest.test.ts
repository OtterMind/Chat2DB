import assert from 'node:assert/strict';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from './latestRequest';

const generationRef = { current: 0 };
const firstSetupRequest = beginLatestRequest(generationRef);

assert.equal(isLatestRequest(generationRef, firstSetupRequest), true);

invalidateLatestRequest(generationRef);
const secondSetupRequest = beginLatestRequest(generationRef);

assert.equal(isLatestRequest(generationRef, firstSetupRequest), false);
assert.equal(isLatestRequest(generationRef, secondSetupRequest), true);

const newerRequest = beginLatestRequest(generationRef);

assert.equal(isLatestRequest(generationRef, secondSetupRequest), false);
assert.equal(isLatestRequest(generationRef, newerRequest), true);
