import assert from 'node:assert/strict';

import { shouldAutoPollTaskCenter } from './taskCenterPolling';

assert.equal(shouldAutoPollTaskCenter({ enabled: false, desktop: false, serviceReady: true }), false);
assert.equal(shouldAutoPollTaskCenter({ enabled: false, desktop: true, serviceReady: true }), false);
assert.equal(shouldAutoPollTaskCenter({ enabled: true, desktop: true, serviceReady: false }), false);
assert.equal(shouldAutoPollTaskCenter({ enabled: true, desktop: true, serviceReady: true }), true);
assert.equal(shouldAutoPollTaskCenter({ enabled: true, desktop: false, serviceReady: false }), true);

console.log('task center polling policy tests passed');
