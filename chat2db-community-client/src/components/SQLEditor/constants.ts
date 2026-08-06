import { EditorSettings, IEditorFontFamily, IEditorTheme } from './type';
import { DEFAULT_SQL_COMPLETION_ACCEPT_KEY } from './core/sqlCompletionAcceptKey';

export const DEFAULT_EDITOR_THEME: IEditorTheme = 'vs-dark';
export const DEFAULT_EDITOR_FONT_FAMILY: IEditorFontFamily = 'Monaco';

export const DEFAULT_EDITOR_SETTINGS: EditorSettings = {
  theme: DEFAULT_EDITOR_THEME,
  lightTheme: 'vs',
  darkTheme: 'vs-dark',
  darkDimmedTheme: 'hc-black',
  fontSize: 14,
  fontFamily: DEFAULT_EDITOR_FONT_FAMILY,
  language: 'sql',
  lineNumbers: 'on',
  minimap: {
    enabled: false,
  },
  wordWrap: 'on',
  lineHeight: 1.6,
  folding: true,
  keywordCase: false,
  completion: [],
  errorContinue: true,
  tableDDLTriggerMode: 'hover',
  completionAcceptKey: DEFAULT_SQL_COMPLETION_ACCEPT_KEY,
  renderLineHighlight: 'line',
  customFontFamily: '',
  stickyScroll: {
    enabled: true,
  },
  confirmBeforeClose: true,
};
