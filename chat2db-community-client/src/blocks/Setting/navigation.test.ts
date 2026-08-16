import assert from 'node:assert/strict';
import { groupSettingMenuItems } from './navigation';

const groups = groupSettingMenuItems([
  { code: 'about', group: 'information' as const },
  { code: 'basic', group: 'general' as const },
  { code: 'networkProxy', group: 'services' as const },
  { code: 'shortcut', group: 'general' as const },
]);

assert.deepEqual(
  groups.map((group) => group.code),
  ['general', 'services', 'information'],
  'navigation groups follow the stable settings hierarchy and omit empty groups',
);
assert.deepEqual(
  groups[0].items.map((item) => item.code),
  ['basic', 'shortcut'],
  'items keep their original order inside a navigation group',
);

console.log('Settings navigation grouping tests passed.');
