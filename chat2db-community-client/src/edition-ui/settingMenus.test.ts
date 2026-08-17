import assert from 'node:assert/strict';
import { appendEditionSettingMenuItems } from './settingMenus';

const shared = [{ code: 'basic' }, { code: 'terminal' }];
const edition = [{ code: 'storageMigration' }];

assert.deepEqual(
  appendEditionSettingMenuItems(shared as never, edition as never).map((item) => item.code),
  ['basic', 'terminal', 'storageMigration'],
  'edition settings append to the complete shared menu',
);
assert.throws(
  () => appendEditionSettingMenuItems(shared as never, [{ code: 'terminal' }] as never),
  /cannot replace shared item: terminal/,
  'edition additions cannot silently replace a shared setting',
);

console.log('Edition setting menu tests passed.');
