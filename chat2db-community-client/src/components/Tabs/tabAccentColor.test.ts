import assert from 'node:assert/strict';
import { getTabAccentStyle } from './tabAccentColor';

assert.deepEqual(getTabAccentStyle('#12AB34'), { '--chat2db-tab-accent-color': '#12AB34' });
assert.deepEqual(getTabAccentStyle(null), {});
assert.deepEqual(getTabAccentStyle(undefined), {});
assert.deepEqual(getTabAccentStyle(''), {});

console.log('Tab accent color tests passed');
