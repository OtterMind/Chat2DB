import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';

const read = (file: string) => readFileSync(file, 'utf8');
const settingLayoutSource = read('src/blocks/Setting/SettingLayout.tsx');
const settingSource = read('src/blocks/Setting/index.tsx');
const clientExtensionSource = read('src/client-extension/community.tsx');
const baseSettingSource = read('src/blocks/Setting/BaseSetting/index.tsx');
const editorSettingSource = read('src/blocks/Setting/EditorSetting/index.tsx');
const terminalSettingSource = read('src/blocks/Setting/TerminalSetting/index.tsx');
const networkProxySettingSource = read('src/blocks/Setting/NetworkProxySetting/index.tsx');
const shortcutSettingSource = read('src/blocks/Setting/ShortcutSetting/index.tsx');
const searchCatalogSource = read('src/blocks/Setting/searchCatalog.ts');
const searchTargetLabelSource = read('src/blocks/Setting/SearchTargetLabel.tsx');
const searchModelSource = read('src/blocks/Setting/search.ts');
const shellStyleSource = read('src/blocks/Setting/style.ts');

assert.match(settingLayoutSource, /<nav\b/);
assert.match(settingLayoutSource, /<main\b/);
assert.match(settingLayoutSource, /<SearchBar\b/);
assert.match(settingLayoutSource, /searchSettings\(/);
assert.match(settingLayoutSource, /getSettingTargetScrollTop/);
assert.match(settingLayoutSource, /isSettingsSearchShortcut/);
assert.match(settingLayoutSource, /data-setting-search-highlighted/);
assert.match(shellStyleSource, /\[data-setting-search-highlighted='true'\]/);
assert.match(searchModelSource, /normalize\('NFKD'\)/);
assert.match(searchTargetLabelSource, /data-setting-search-id=\{targetId\}/);
assert.match(settingLayoutSource, /aria-current=\{isActive \? 'page'/);

assert.match(settingSource, /clientExtension\.settings\?\.items/);
assert.match(settingSource, /body: <TerminalSetting \/>/);
assert.doesNotMatch(clientExtensionSource, /settings:/);
assert.doesNotMatch(settingSource, /Personal|Invite|PurchaseDetails|DeviceCer|License/);

for (const removedPath of [
  'src/blocks/Setting/Personal',
  'src/blocks/Setting/Invite',
  'src/blocks/Setting/DeviceCer',
  'src/blocks/Setting/License',
  'src/components/PurchaseDetails',
]) {
  assert.equal(existsSync(removedPath), false, `${removedPath} is owned outside Community`);
}

for (const targetId of [
  'basic.appearance',
  'basic.language',
  'basic.typography',
  'editor.theme',
  'editor.fontFamily',
  'editor.fontSize',
  'editor.completion',
  'editor.confirmBeforeClose',
  'editor.defaultPageSize',
  'terminal.position',
  'terminal.shell',
  'shortcut.global',
  'shortcut.workspace',
  'shortcut.localSqlFileTree',
  'shortcut.sqlEditor',
  'shortcut.resultSet',
  'shortcut.table',
  'mcp.token',
  'networkProxy.mode',
  'networkProxy.test',
]) {
  assert.ok(searchCatalogSource.includes(`'${targetId}'`), `${targetId} is indexed for settings search`);
}

assert.match(baseSettingSource, /<Palette\b/);
assert.match(baseSettingSource, /<Globe\b/);
assert.match(editorSettingSource, /<ShieldCheck\b/);
assert.match(editorSettingSource, /name="defaultPageSize"/);
assert.match(editorSettingSource, /setBaseSetting\(\{ defaultPageSize:/);
assert.match(terminalSettingSource, /<Switch\b/);
assert.doesNotMatch(networkProxySettingSource, /SettingSubsection/);
assert.match(shortcutSettingSource, /aria-expanded=\{!collapsed\}/);

console.log('Settings layout contract tests passed.');
