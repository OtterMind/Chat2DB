import assert from 'node:assert/strict';
import {
  getSettingTargetScrollTop,
  isSettingsSearchShortcut,
  searchSettings,
  type SearchableSettingMenu,
} from './search';

const menus: SearchableSettingMenu[] = [
  {
    code: 'basic',
    title: '基础设置',
    describe: '',
    searchItems: [
      { targetId: 'basic.appearance', title: '外观', keywords: ['theme', '主题色'] },
      { targetId: 'basic.language', title: '语言', keywords: ['locale'] },
    ],
  },
  {
    code: 'editSetting',
    title: '编辑器设置',
    describe: '配置 SQL 编辑体验',
    searchItems: [
      { targetId: 'editor.stickyScroll', title: '粘性滚动', keywords: ['sticky scroll'] },
      { targetId: 'editor.completionAcceptKey', title: '补全接受键', keywords: ['completion key', 'enter', 'tab'] },
    ],
  },
];

assert.deepEqual(searchSettings(menus, ''), [], 'blank queries do not enter search mode');
assert.equal(searchSettings(menus, '编辑器')[0]?.key, 'editSetting:page', 'page names are searchable');
assert.equal(searchSettings(menus, '编辑体验')[0]?.key, 'editSetting:page', 'page descriptions are searchable');
assert.equal(
  searchSettings(menus, '粘性')[0]?.targetId,
  'editor.stickyScroll',
  'localized setting names are searchable',
);
assert.equal(
  searchSettings(menus, 'sticky scroll')[0]?.targetId,
  'editor.stickyScroll',
  'multi-word aliases are searchable',
);
assert.equal(searchSettings(menus, 'locale')[0]?.targetId, 'basic.language', 'technical aliases are searchable');
assert.equal(searchSettings(menus, 'missing').length, 0, 'unknown terms return no results');
assert.equal(
  isSettingsSearchShortcut({ altKey: false, code: 'KeyF', ctrlKey: false, metaKey: true, shiftKey: false }),
  true,
  'Cmd+F focuses settings search',
);
assert.equal(
  isSettingsSearchShortcut({ altKey: false, code: 'KeyF', ctrlKey: true, metaKey: false, shiftKey: false }),
  true,
  'Ctrl+F focuses settings search',
);
assert.equal(
  isSettingsSearchShortcut({ altKey: false, code: 'KeyF', ctrlKey: false, metaKey: false, shiftKey: false }),
  false,
  'plain F remains available to controls',
);
assert.equal(
  isSettingsSearchShortcut({ altKey: false, code: 'KeyF', ctrlKey: true, metaKey: true, shiftKey: false }),
  false,
  'combined system modifiers are not treated as settings search',
);
assert.equal(
  getSettingTargetScrollTop({
    containerHeight: 600,
    containerTop: 100,
    scrollTop: 200,
    targetHeight: 40,
    targetTop: 700,
  }),
  520,
  'the exact setting is centered in the settings content scroller',
);
assert.equal(
  getSettingTargetScrollTop({
    containerHeight: 600,
    containerTop: 100,
    scrollTop: 0,
    targetHeight: 40,
    targetTop: 120,
  }),
  0,
  'centering never requests a negative scroll position',
);

console.log('Settings search tests passed.');
