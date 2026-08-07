import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const settingLayoutSource = readFileSync('src/blocks/Setting/SettingLayout.tsx', 'utf8');
const communitySettingSource = readFileSync('src/blocks/Setting/CommunitySetting.tsx', 'utf8');
const settingSource = readFileSync('src/blocks/Setting/index.tsx', 'utf8');
const baseSettingSource = readFileSync('src/blocks/Setting/BaseSetting/index.tsx', 'utf8');
const editorSettingSource = readFileSync('src/blocks/Setting/EditorSetting/index.tsx', 'utf8');
const terminalSettingSource = readFileSync('src/blocks/Setting/TerminalSetting/index.tsx', 'utf8');
const mcpSettingSource = readFileSync('src/blocks/Setting/McpSetting/index.tsx', 'utf8');
const networkProxySettingSource = readFileSync('src/blocks/Setting/NetworkProxySetting/index.tsx', 'utf8');
const shortcutSettingSource = readFileSync('src/blocks/Setting/ShortcutSetting/index.tsx', 'utf8');
const searchCatalogSource = readFileSync('src/blocks/Setting/searchCatalog.ts', 'utf8');
const searchTargetLabelSource = readFileSync('src/blocks/Setting/SearchTargetLabel.tsx', 'utf8');
const searchModelSource = readFileSync('src/blocks/Setting/search.ts', 'utf8');
const shellStyleSource = readFileSync('src/blocks/Setting/style.ts', 'utf8');
const baseStyleSource = readFileSync('src/blocks/Setting/BaseSetting/style.ts', 'utf8');
const editorStyleSource = readFileSync('src/blocks/Setting/EditorSetting/style.ts', 'utf8');

const appearanceGroupIndex = baseSettingSource.indexOf('data-setting-group="appearance"');
const languageGroupIndex = baseSettingSource.indexOf('data-setting-group="language"');
const typographyGroupIndex = baseSettingSource.indexOf('data-setting-group="typography"');
const editorAppearanceGroupIndex = editorSettingSource.indexOf('data-editor-setting-group="appearance"');
const editorDisplayGroupIndex = editorSettingSource.indexOf('data-editor-setting-group="display"');
const editorCompletionGroupIndex = editorSettingSource.indexOf('data-editor-setting-group="completion"');
const editorBehaviorGroupIndex = editorSettingSource.indexOf('data-editor-setting-group="behavior"');
const editorExecutionGroupIndex = editorSettingSource.indexOf('data-editor-setting-group="execution"');
const editorTooltipKeys = [
  'monaco.lineHeight.tooltip',
  'monaco.minimap.tooltip',
  'monaco.wordWrap.tooltip',
  'monaco.folding.tooltip',
  'monaco.renderLineHighlight.tooltip',
  'monaco.stickyScroll.tooltip',
  'monaco.keywordCase.tooltip',
  'monaco.completionAcceptKey.tooltip',
  'monaco.completion.all.tooltip',
  'monaco.tableDDLTriggerMode.tooltip',
  'monaco.confirmBeforeClose.tooltip',
  'monaco.errorContinue.tooltip',
];
const settingSearchTargetIds = [
  'basic.appearance',
  'basic.language',
  'basic.typography',
  'editor.theme',
  'editor.fontFamily',
  'editor.customFontFamily',
  'editor.fontSize',
  'editor.lineHeight',
  'editor.lineNumbers',
  'editor.minimap',
  'editor.wordWrap',
  'editor.folding',
  'editor.renderLineHighlight',
  'editor.stickyScroll',
  'editor.keywordCase',
  'editor.completionAcceptKey',
  'editor.completion',
  'editor.tableDDLTriggerMode',
  'editor.confirmBeforeClose',
  'editor.errorContinue',
  'terminal.position',
  'terminal.confirmBeforeClose',
  'terminal.shell',
  'terminal.theme',
  'shortcut.global',
  'shortcut.workspace',
  'shortcut.localSqlFileTree',
  'shortcut.sqlEditor',
  'shortcut.resultSet',
  'shortcut.table',
  'mcp.token',
  'networkProxy.mode',
  'networkProxy.test',
];
const searchableSettingSources = [
  baseSettingSource,
  editorSettingSource,
  terminalSettingSource,
  mcpSettingSource,
  networkProxySettingSource,
  shortcutSettingSource,
].join('\n');
const hiddenNetworkProxyTargetIds = [
  'networkProxy.type',
  'networkProxy.host',
  'networkProxy.port',
  'networkProxy.noProxyHosts',
];

assert.match(settingLayoutSource, /<nav\b/, 'the settings shell exposes navigation semantics');
assert.match(settingLayoutSource, /<main\b/, 'the settings shell exposes a main content landmark');
assert.match(settingLayoutSource, /<SearchBar\b/, 'settings reuse the data browser search component');
assert.match(settingLayoutSource, /searchSettings\(/, 'settings search uses the shared matching model');
assert.match(settingLayoutSource, /menuContent\.scrollTo/, 'search results scroll the settings content directly');
assert.match(
  settingLayoutSource,
  /getSettingTargetScrollTop/,
  'search results center the exact setting in the viewport',
);
assert.match(settingLayoutSource, /isSettingsSearchShortcut/, 'settings search handles Cmd/Ctrl+F');
assert.match(settingLayoutSource, /ref=\{searchBarRef\}/, 'the settings shortcut focuses the shared search bar');
assert.match(settingLayoutSource, /selectedSearchResultKey/, 'the selected search result remains visible');
assert.match(
  settingLayoutSource,
  /data-setting-search-highlighted/,
  'the selected setting receives a highlight target',
);
assert.match(
  settingLayoutSource,
  /dataset\.settingSearchExpandable[\s\S]*?target\.click\(\)/,
  'searching a collapsed settings group expands it before navigation',
);
assert.match(shellStyleSource, /searchResultActive/, 'selected search results use an active style');
assert.match(
  shellStyleSource,
  /\[data-setting-search-highlighted='true'\]/,
  'the exact setting title uses a highlighted style',
);
assert.match(searchModelSource, /normalize\('NFKD'\)/, 'settings search is accent insensitive');
assert.match(
  searchTargetLabelSource,
  /data-setting-search-id=\{targetId\}/,
  'field search targets render on a real label element',
);
assert.match(settingLayoutSource, /aria-current=\{isActive \? 'page'/, 'the active settings destination is announced');
assert.match(communitySettingSource, /<SettingLayout\b/, 'the Community entry uses the shared settings shell');
assert.match(settingLayoutSource, /<IconfontSvg\b/, 'settings navigation supports retained product icons');
assert.match(communitySettingSource, /iconCode: 'icon-mcp'/, 'Community MCP retains its product icon');
assert.match(settingSource, /iconCode: 'icon-mcp'/, 'other editions retain the MCP product icon');
assert.match(communitySettingSource, /iconCode: 'icon-wangluo'/, 'Community proxy retains its product icon');
assert.match(settingSource, /iconCode: 'icon-wangluo'/, 'other editions retain the proxy product icon');
assert.match(communitySettingSource, /icon: ClipboardPen/, 'Community editor settings use ClipboardPen');
assert.match(settingSource, /icon: ClipboardPen/, 'other editions use ClipboardPen for editor settings');
assert.match(baseSettingSource, /<Palette\b/, 'the appearance group uses the Lucide palette icon');
assert.match(baseSettingSource, /<Globe\b/, 'the language group uses the Lucide globe icon');
assert.match(baseSettingSource, /<button[\s\S]*?aria-pressed=\{isActive\}/, 'theme choices are native buttons');
assert.match(baseSettingSource, /type="button"/, 'theme buttons do not submit surrounding forms');
assert.match(baseSettingSource, /styles\.colorSwatch/, 'theme colors use keyboard-accessible swatch buttons');
assert.ok(appearanceGroupIndex >= 0, 'basic settings expose an appearance group');
assert.ok(languageGroupIndex > appearanceGroupIndex, 'language follows appearance');
assert.ok(typographyGroupIndex > languageGroupIndex, 'interface typography follows language');
assert.ok(editorAppearanceGroupIndex >= 0, 'editor settings expose an appearance group');
assert.ok(editorDisplayGroupIndex > editorAppearanceGroupIndex, 'editor display follows appearance');
assert.ok(editorCompletionGroupIndex > editorDisplayGroupIndex, 'completion follows editor display');
assert.ok(editorBehaviorGroupIndex > editorCompletionGroupIndex, 'editor behavior follows completion');
assert.ok(editorExecutionGroupIndex > editorBehaviorGroupIndex, 'SQL execution follows editor behavior');
assert.match(editorSettingSource, /<Palette\b/, 'editor appearance uses a Lucide icon');
assert.match(editorSettingSource, /<Monitor\b/, 'editor display uses a Lucide icon');
assert.match(editorSettingSource, /<Braces\b/, 'editor completion uses a Lucide icon');
assert.match(editorSettingSource, /<ShieldCheck\b/, 'editor close behavior uses a Lucide icon');
assert.match(editorSettingSource, /<Play\b/, 'editor execution uses a Lucide icon');
assert.match(
  editorSettingSource,
  /name="confirmBeforeClose"[\s\S]*?valuePropName="checked"[\s\S]*?<Switch\b/,
  'editor close confirmation uses a persisted binary switch',
);
assert.match(terminalSettingSource, /<Switch\b/, 'terminal close confirmation uses a binary switch');
assert.match(
  terminalSettingSource,
  /checked=\{terminalSettings\.confirmBeforeClose\}/,
  'terminal close confirmation reflects the persisted preference',
);
for (const tooltipKey of editorTooltipKeys) {
  assert.ok(editorSettingSource.includes(`i18n('${tooltipKey}'`), `${tooltipKey} is exposed as contextual help`);
}
for (const targetId of settingSearchTargetIds) {
  assert.ok(searchCatalogSource.includes(`'${targetId}'`), `${targetId} is indexed for settings search`);
  assert.ok(
    searchableSettingSources.includes(`data-setting-search-id="${targetId}"`) ||
      searchableSettingSources.includes(`targetId="${targetId}"`) ||
      searchableSettingSources.includes(`'${targetId}'`),
    `${targetId} has a navigable page target`,
  );
}
for (const targetId of hiddenNetworkProxyTargetIds) {
  assert.ok(
    !searchCatalogSource.includes(`'${targetId}'`),
    `${targetId} is omitted because the control only exists in manual proxy mode`,
  );
}
assert.match(
  editorSettingSource,
  /monaco\.renderLineHighlight\.line/,
  'line highlight choices use readable localized labels',
);
assert.match(shellStyleSource, /@media \(max-width: 760px\)/, 'the settings shell defines a narrow layout');
assert.match(
  baseStyleSource,
  /container-type:\s*inline-size/,
  'basic settings respond to their available content width',
);
assert.match(baseStyleSource, /@container \(max-width:/, 'setting rows stack before controls become cramped');
assert.match(editorStyleSource, /@container \(max-width:/, 'editor settings stack within a narrow content area');
assert.match(editorStyleSource, /height:\s*clamp\(420px/, 'the Monaco preview has a stable parent height');
assert.doesNotMatch(editorSettingSource, /SettingSubsection/, 'the shared page header owns the editor title');
assert.doesNotMatch(networkProxySettingSource, /SettingSubsection/, 'the shared page header owns the proxy title');
assert.doesNotMatch(shortcutSettingSource, /SettingSubsection/, 'the shared page header owns the shortcut title');
assert.doesNotMatch(
  mcpSettingSource,
  /SettingSubsection title=\{i18n\('setting\.title\.mcp'\)\}/,
  'the shared page header owns the MCP title',
);
assert.match(shortcutSettingSource, /aria-expanded=\{!collapsed\}/, 'shortcut groups expose their expanded state');
assert.match(
  shortcutSettingSource,
  /data-setting-search-expandable="true"/,
  'shortcut search results can expand collapsed groups',
);
assert.match(
  communitySettingSource,
  /hidePageHeader: true,[\s\S]*?body: <About \/>/,
  'Community About renders its product information without a duplicate shared header',
);
assert.match(
  settingSource,
  /hidePageHeader: true,[\s\S]*?body: <About \/>/,
  'other editions render About without a duplicate shared header',
);

console.log('Settings layout contract tests passed.');
