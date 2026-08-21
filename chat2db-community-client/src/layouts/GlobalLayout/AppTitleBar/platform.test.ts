import assert from 'node:assert/strict';

import { Platform } from '@/constants/os';
import { resolveTitleBarPlatform } from './platform';

assert.deepEqual(resolveTitleBarPlatform(Platform.Mac, 'Windows NT 10.0'), {
  isMac: true,
  isWindows: false,
});
assert.deepEqual(resolveTitleBarPlatform(undefined, 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)'), {
  isMac: true,
  isWindows: false,
});
assert.deepEqual(resolveTitleBarPlatform(undefined, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'), {
  isMac: false,
  isWindows: true,
});
assert.deepEqual(resolveTitleBarPlatform(Platform.Linux, 'Mozilla/5.0 (Macintosh)'), {
  isMac: false,
  isWindows: false,
});

console.log('App title bar platform tests passed.');
