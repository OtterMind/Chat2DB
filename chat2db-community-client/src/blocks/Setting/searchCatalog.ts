import i18n from '@/i18n';
import type { SettingSearchItem } from './search';

export function getSettingSearchItems(menuCode: string): SettingSearchItem[] {
  switch (menuCode) {
    case 'basic':
      return [
        {
          targetId: 'basic.appearance',
          title: i18n('setting.title.appearance'),
          keywords: [
            i18n('setting.title.backgroundColor'),
            i18n('setting.title.themeColor'),
            i18n('setting.text.light'),
            i18n('setting.text.dark'),
            'appearance',
            'theme',
            'color',
          ],
        },
        {
          targetId: 'basic.language',
          title: i18n('setting.title.language'),
          keywords: ['language', 'locale'],
        },
        {
          targetId: 'basic.typography',
          title: i18n('setting.title.interfaceFont'),
          keywords: [i18n('setting.title.customFont'), i18n('setting.title.customFontSize'), 'font', 'typography'],
        },
      ];
    case 'editSetting':
      return [
        settingItem('editor.theme', 'monaco.theme', ['theme']),
        settingItem('editor.fontFamily', 'monaco.fontFamily', ['font']),
        settingItem('editor.customFontFamily', 'setting.title.customFont', ['custom font']),
        settingItem('editor.fontSize', 'monaco.fontSize', ['font size']),
        settingItem('editor.lineHeight', 'monaco.lineHeight', ['line height']),
        settingItem('editor.lineNumbers', 'monaco.lineNumbers', ['line number']),
        settingItem('editor.minimap', 'monaco.minimap', ['minimap']),
        settingItem('editor.wordWrap', 'monaco.wordWrap', ['word wrap']),
        settingItem('editor.folding', 'monaco.folding', ['folding']),
        settingItem('editor.renderLineHighlight', 'monaco.renderLineHighlight', ['line highlight', 'gutter']),
        settingItem('editor.stickyScroll', 'monaco.stickyScroll', ['sticky scroll']),
        settingItem('editor.keywordCase', 'monaco.keywordCase', ['keyword case', 'upper', 'lower']),
        settingItem('editor.completionAcceptKey', 'monaco.completionAcceptKey', ['completion key', 'enter', 'tab']),
        settingItem('editor.completion', 'monaco.completion.all', ['qualified completion', 'autocomplete']),
        settingItem('editor.tableDDLTriggerMode', 'monaco.tableDDLTriggerMode', ['ddl', 'hover', 'click']),
        settingItem('editor.errorContinue', 'monaco.errorContinue', ['continue on error', 'execution']),
      ];
    case 'shortcut':
      return [
        settingItem('shortcut.global', 'setting.shortcut.group.global', ['application', 'app']),
        settingItem('shortcut.workspace', 'setting.shortcut.group.workspace', ['workspace']),
        settingItem('shortcut.localSqlFileTree', 'setting.shortcut.group.localSqlFileTree', [
          'text file',
          'sql file',
          'file tree',
        ]),
        settingItem('shortcut.sqlEditor', 'setting.shortcut.group.sqlEditor', ['sql editor', 'query editor']),
        settingItem('shortcut.resultSet', 'setting.shortcut.group.resultSet', ['result', 'result set']),
        settingItem('shortcut.table', 'setting.shortcut.group.table', ['table', 'grid']),
      ];
    case 'mcp':
      return [
        {
          targetId: 'mcp.token',
          title: i18n('setting.title.mcpToken'),
          keywords: ['mcp token', 'authentication', 'reset token'],
        },
      ];
    case 'networkProxy':
      return [
        {
          targetId: 'networkProxy.mode',
          title: i18n('setting.networkProxy.mode'),
          keywords: ['proxy mode', 'direct', 'system proxy', 'manual proxy'],
        },
        {
          targetId: 'networkProxy.test',
          title: i18n('setting.networkProxy.testUrl'),
          keywords: [i18n('setting.networkProxy.testConnection'), 'test url', 'connection test'],
        },
      ];
    default:
      return [];
  }
}

function settingItem(targetId: string, titleKey: Parameters<typeof i18n>[0], keywords: string[]): SettingSearchItem {
  return {
    targetId,
    title: i18n(titleKey),
    keywords,
  };
}
