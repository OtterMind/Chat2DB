import assert from 'node:assert/strict';
import { appendClientSettingMenuItems } from './settingMenus';

const shared = [{ code: 'basic' }, { code: 'terminal' }];
const extension = [{ code: 'advancedExtension' }];

assert.deepEqual(
  appendClientSettingMenuItems(shared as never, extension as never).map((item) => item.code),
  ['basic', 'terminal', 'advancedExtension'],
  'client settings append to the complete shared menu',
);
assert.throws(
  () => appendClientSettingMenuItems(shared as never, [{ code: 'terminal' }] as never),
  /cannot replace shared item: terminal/,
  'edition additions cannot silently replace a shared setting',
);

console.log('Client setting menu tests passed.');
