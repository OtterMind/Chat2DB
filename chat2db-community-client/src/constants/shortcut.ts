import { isMac } from '@/utils/env';
import { getFileManagerLabelKey } from '@/utils/fileManagerLabel';

export enum ShortcutScope {
  Global = 'global',
  SqlEditor = 'sqlEditor',
  LocalSqlFileTree = 'localSqlFileTree',
  DatabaseTree = 'databaseTree',
  ResultSet = 'resultSet',
  Workspace = 'workspace',
  Table = 'table',
}

export enum ShortcutAction {
  OpenSetting = 'openSetting',
  ZoomIn = 'zoomIn',
  ZoomOut = 'zoomOut',
  ZoomReset = 'zoomReset',
  SwitchToWorkspace = 'switchToWorkspace',
  SwitchToDashboard = 'switchToDashboard',
  SwitchToChat = 'switchToChat',
  CloseCurrentConsole = 'closeCurrentConsole',
  NewConsole = 'newConsole',
  ArouseAIAssistant = 'arouseAIAssistant',
  NewAIChat = 'newAIChat',
  WorkspaceTreeSearch = 'workspaceTreeSearch',
  DatabaseTreeOpenTable = 'databaseTreeOpenTable',
  DatabaseTreeEditTable = 'databaseTreeEditTable',
  DatabaseTreeCreateTable = 'databaseTreeCreateTable',
  DatabaseTreeRefresh = 'databaseTreeRefresh',
  LocalSqlFileTreeOpenFolder = 'localSqlFileTreeOpenFolder',
  LocalSqlFileTreeAddFolder = 'localSqlFileTreeAddFolder',
  LocalSqlFileTreeOpen = 'localSqlFileTreeOpen',
  LocalSqlFileTreeRename = 'localSqlFileTreeRename',
  LocalSqlFileTreeDelete = 'localSqlFileTreeDelete',
  LocalSqlFileTreeRemoveRoot = 'localSqlFileTreeRemoveRoot',
  LocalSqlFileTreeNewFile = 'localSqlFileTreeNewFile',
  LocalSqlFileTreeNewFolder = 'localSqlFileTreeNewFolder',
  LocalSqlFileTreeRefresh = 'localSqlFileTreeRefresh',
  LocalSqlFileTreeCollapseAll = 'localSqlFileTreeCollapseAll',
  LocalSqlFileTreeRevealInFinder = 'localSqlFileTreeRevealInFinder',
  LocalSqlFileTreeOpenTerminal = 'localSqlFileTreeOpenTerminal',
  LocalSqlFileTreeCopyPath = 'localSqlFileTreeCopyPath',
  LocalSqlFileTreeCopyRelativePath = 'localSqlFileTreeCopyRelativePath',
  SqlCopy = 'sqlCopy',
  SqlPaste = 'sqlPaste',
  SqlCut = 'sqlCut',
  SqlCaseConvert = 'sqlCaseConvert',
  SqlFormat = 'sqlFormat',
  SqlToggleLineComment = 'sqlToggleLineComment',
  SqlToggleBlockComment = 'sqlToggleBlockComment',
  SqlSave = 'sqlSave',
  SqlSaveToDesktop = 'sqlSaveToDesktop',
  SqlExecuteCurrent = 'sqlExecuteCurrent',
  SqlExecuteAll = 'sqlExecuteAll',
  SqlInsertValueHint = 'sqlInsertValueHint',
  SqlOpenEditorSetting = 'sqlOpenEditorSetting',
  ResultSearch = 'resultSearch',
  ResultSubmit = 'resultSubmit',
  ResultRefresh = 'resultRefresh',
  TableCopy = 'tableCopy',
  TablePaste = 'tablePaste',
  TableSelectAll = 'tableSelectAll',
}

export interface ShortcutDefinition {
  key: ShortcutAction;
  label: string;
  action: ShortcutAction;
  defaultBinding: string;
  scope: ShortcutScope;
  allowInEditable?: boolean;
  canModify?: boolean;
}

export interface ShortcutOverride {
  binding: string | null;
}

export type ShortcutOverrides = Partial<Record<ShortcutAction, ShortcutOverride>>;

export interface EffectiveShortcutConfig extends ShortcutDefinition {
  binding: string | null;
  disabled: boolean;
  isDefault: boolean;
}

const modifierKey = isMac ? '⌘' : 'Ctrl';
const deleteKey = isMac ? 'Backspace' : 'Delete';
const localSqlFileTreeRevealLabel = getFileManagerLabelKey('shortcut');

export const DEFAULT_SHORTCUT_CONFIG: Record<ShortcutAction, ShortcutDefinition> = {
  [ShortcutAction.OpenSetting]: {
    key: ShortcutAction.OpenSetting,
    label: 'setting.shortcut.openSetting',
    action: ShortcutAction.OpenSetting,
    defaultBinding: `${modifierKey} + 4`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.ZoomIn]: {
    key: ShortcutAction.ZoomIn,
    label: 'setting.shortcut.zoomIn',
    action: ShortcutAction.ZoomIn,
    defaultBinding: `${modifierKey} + =`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.ZoomOut]: {
    key: ShortcutAction.ZoomOut,
    label: 'setting.shortcut.zoomOut',
    action: ShortcutAction.ZoomOut,
    defaultBinding: `${modifierKey} + -`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.ZoomReset]: {
    key: ShortcutAction.ZoomReset,
    label: 'setting.shortcut.zoomReset',
    action: ShortcutAction.ZoomReset,
    defaultBinding: `${modifierKey} + 0`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SwitchToWorkspace]: {
    key: ShortcutAction.SwitchToWorkspace,
    label: 'setting.shortcut.switchWorkspace',
    action: ShortcutAction.SwitchToWorkspace,
    defaultBinding: `${modifierKey} + 2`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SwitchToDashboard]: {
    key: ShortcutAction.SwitchToDashboard,
    label: 'setting.shortcut.switchDashboard',
    action: ShortcutAction.SwitchToDashboard,
    defaultBinding: `${modifierKey} + 3`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SwitchToChat]: {
    key: ShortcutAction.SwitchToChat,
    label: 'setting.shortcut.switchChat',
    action: ShortcutAction.SwitchToChat,
    defaultBinding: `${modifierKey} + 1`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.CloseCurrentConsole]: {
    key: ShortcutAction.CloseCurrentConsole,
    label: 'setting.shortcut.closeConsole',
    action: ShortcutAction.CloseCurrentConsole,
    defaultBinding: `${modifierKey} + W`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.NewConsole]: {
    key: ShortcutAction.NewConsole,
    label: 'setting.shortcut.newConsole',
    action: ShortcutAction.NewConsole,
    defaultBinding: `${modifierKey} + T`,
    scope: ShortcutScope.Global,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.ArouseAIAssistant]: {
    key: ShortcutAction.ArouseAIAssistant,
    label: 'setting.shortcut.arouseAIAssistant',
    action: ShortcutAction.ArouseAIAssistant,
    defaultBinding: `${modifierKey} + K`,
    scope: ShortcutScope.Global,
    canModify: true,
  },
  [ShortcutAction.NewAIChat]: {
    key: ShortcutAction.NewAIChat,
    label: 'setting.shortcut.newAIChat',
    action: ShortcutAction.NewAIChat,
    defaultBinding: `${modifierKey} + L`,
    scope: ShortcutScope.Global,
    canModify: true,
  },
  [ShortcutAction.WorkspaceTreeSearch]: {
    key: ShortcutAction.WorkspaceTreeSearch,
    label: 'setting.shortcut.workspaceTreeSearch',
    action: ShortcutAction.WorkspaceTreeSearch,
    defaultBinding: `${modifierKey} + F`,
    scope: ShortcutScope.Workspace,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.DatabaseTreeOpenTable]: {
    key: ShortcutAction.DatabaseTreeOpenTable,
    label: 'setting.shortcut.databaseTreeOpenTable',
    action: ShortcutAction.DatabaseTreeOpenTable,
    defaultBinding: `${modifierKey} + O`,
    scope: ShortcutScope.DatabaseTree,
    canModify: true,
  },
  [ShortcutAction.DatabaseTreeEditTable]: {
    key: ShortcutAction.DatabaseTreeEditTable,
    label: 'setting.shortcut.databaseTreeEditTable',
    action: ShortcutAction.DatabaseTreeEditTable,
    defaultBinding: `${modifierKey} + D`,
    scope: ShortcutScope.DatabaseTree,
    canModify: true,
  },
  [ShortcutAction.DatabaseTreeCreateTable]: {
    key: ShortcutAction.DatabaseTreeCreateTable,
    label: 'setting.shortcut.databaseTreeCreateTable',
    action: ShortcutAction.DatabaseTreeCreateTable,
    defaultBinding: `${modifierKey} + N`,
    scope: ShortcutScope.DatabaseTree,
    canModify: true,
  },
  [ShortcutAction.DatabaseTreeRefresh]: {
    key: ShortcutAction.DatabaseTreeRefresh,
    label: 'setting.shortcut.databaseTreeRefresh',
    action: ShortcutAction.DatabaseTreeRefresh,
    defaultBinding: `${modifierKey} + R`,
    scope: ShortcutScope.DatabaseTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeOpenFolder]: {
    key: ShortcutAction.LocalSqlFileTreeOpenFolder,
    label: 'setting.shortcut.localSqlFileTreeOpenFolder',
    action: ShortcutAction.LocalSqlFileTreeOpenFolder,
    defaultBinding: `${modifierKey} + O`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeAddFolder]: {
    key: ShortcutAction.LocalSqlFileTreeAddFolder,
    label: 'setting.shortcut.localSqlFileTreeAddFolder',
    action: ShortcutAction.LocalSqlFileTreeAddFolder,
    defaultBinding: `${modifierKey} + Shift + O`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeOpen]: {
    key: ShortcutAction.LocalSqlFileTreeOpen,
    label: 'setting.shortcut.localSqlFileTreeOpen',
    action: ShortcutAction.LocalSqlFileTreeOpen,
    defaultBinding: 'Enter',
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeRename]: {
    key: ShortcutAction.LocalSqlFileTreeRename,
    label: 'setting.shortcut.localSqlFileTreeRename',
    action: ShortcutAction.LocalSqlFileTreeRename,
    defaultBinding: 'F2',
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeDelete]: {
    key: ShortcutAction.LocalSqlFileTreeDelete,
    label: 'setting.shortcut.localSqlFileTreeDelete',
    action: ShortcutAction.LocalSqlFileTreeDelete,
    defaultBinding: deleteKey,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeRemoveRoot]: {
    key: ShortcutAction.LocalSqlFileTreeRemoveRoot,
    label: 'setting.shortcut.localSqlFileTreeRemoveRoot',
    action: ShortcutAction.LocalSqlFileTreeRemoveRoot,
    defaultBinding: `${modifierKey} + Shift + ${deleteKey}`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeNewFile]: {
    key: ShortcutAction.LocalSqlFileTreeNewFile,
    label: 'setting.shortcut.localSqlFileTreeNewFile',
    action: ShortcutAction.LocalSqlFileTreeNewFile,
    defaultBinding: `${modifierKey} + N`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeNewFolder]: {
    key: ShortcutAction.LocalSqlFileTreeNewFolder,
    label: 'setting.shortcut.localSqlFileTreeNewFolder',
    action: ShortcutAction.LocalSqlFileTreeNewFolder,
    defaultBinding: `${modifierKey} + Shift + N`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeRefresh]: {
    key: ShortcutAction.LocalSqlFileTreeRefresh,
    label: 'setting.shortcut.localSqlFileTreeRefresh',
    action: ShortcutAction.LocalSqlFileTreeRefresh,
    defaultBinding: `${modifierKey} + R`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeCollapseAll]: {
    key: ShortcutAction.LocalSqlFileTreeCollapseAll,
    label: 'setting.shortcut.localSqlFileTreeCollapseAll',
    action: ShortcutAction.LocalSqlFileTreeCollapseAll,
    defaultBinding: `${modifierKey} + Shift + C`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeRevealInFinder]: {
    key: ShortcutAction.LocalSqlFileTreeRevealInFinder,
    label: localSqlFileTreeRevealLabel,
    action: ShortcutAction.LocalSqlFileTreeRevealInFinder,
    defaultBinding: `${modifierKey} + Alt + R`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeOpenTerminal]: {
    key: ShortcutAction.LocalSqlFileTreeOpenTerminal,
    label: 'setting.shortcut.localSqlFileTreeOpenTerminal',
    action: ShortcutAction.LocalSqlFileTreeOpenTerminal,
    defaultBinding: `${modifierKey} + Alt + T`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeCopyPath]: {
    key: ShortcutAction.LocalSqlFileTreeCopyPath,
    label: 'setting.shortcut.localSqlFileTreeCopyPath',
    action: ShortcutAction.LocalSqlFileTreeCopyPath,
    defaultBinding: `${modifierKey} + Alt + C`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.LocalSqlFileTreeCopyRelativePath]: {
    key: ShortcutAction.LocalSqlFileTreeCopyRelativePath,
    label: 'setting.shortcut.localSqlFileTreeCopyRelativePath',
    action: ShortcutAction.LocalSqlFileTreeCopyRelativePath,
    defaultBinding: `${modifierKey} + Shift + Alt + C`,
    scope: ShortcutScope.LocalSqlFileTree,
    canModify: true,
  },
  [ShortcutAction.SqlCopy]: {
    key: ShortcutAction.SqlCopy,
    label: 'setting.shortcut.sqlCopy',
    action: ShortcutAction.SqlCopy,
    defaultBinding: `${modifierKey} + C`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlPaste]: {
    key: ShortcutAction.SqlPaste,
    label: 'setting.shortcut.sqlPaste',
    action: ShortcutAction.SqlPaste,
    defaultBinding: `${modifierKey} + V`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlCut]: {
    key: ShortcutAction.SqlCut,
    label: 'setting.shortcut.sqlCut',
    action: ShortcutAction.SqlCut,
    defaultBinding: `${modifierKey} + X`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlCaseConvert]: {
    key: ShortcutAction.SqlCaseConvert,
    label: 'setting.shortcut.sqlCaseConvert',
    action: ShortcutAction.SqlCaseConvert,
    defaultBinding: `${modifierKey} + Shift + U`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlFormat]: {
    key: ShortcutAction.SqlFormat,
    label: 'setting.shortcut.sqlFormat',
    action: ShortcutAction.SqlFormat,
    defaultBinding: `${modifierKey} + L`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlToggleLineComment]: {
    key: ShortcutAction.SqlToggleLineComment,
    label: 'setting.shortcut.sqlToggleLineComment',
    action: ShortcutAction.SqlToggleLineComment,
    defaultBinding: `${modifierKey} + /`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlToggleBlockComment]: {
    key: ShortcutAction.SqlToggleBlockComment,
    label: 'setting.shortcut.sqlToggleBlockComment',
    action: ShortcutAction.SqlToggleBlockComment,
    defaultBinding: `${modifierKey} + Shift + /`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlSave]: {
    key: ShortcutAction.SqlSave,
    label: 'setting.shortcut.sqlSave',
    action: ShortcutAction.SqlSave,
    defaultBinding: `${modifierKey} + S`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlSaveToDesktop]: {
    key: ShortcutAction.SqlSaveToDesktop,
    label: 'setting.shortcut.sqlSaveToDesktop',
    action: ShortcutAction.SqlSaveToDesktop,
    defaultBinding: `${modifierKey} + Shift + S`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlExecuteCurrent]: {
    key: ShortcutAction.SqlExecuteCurrent,
    label: 'setting.shortcut.sqlExecuteCurrent',
    action: ShortcutAction.SqlExecuteCurrent,
    defaultBinding: `${modifierKey} + Enter`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlExecuteAll]: {
    key: ShortcutAction.SqlExecuteAll,
    label: 'setting.shortcut.sqlExecuteAll',
    action: ShortcutAction.SqlExecuteAll,
    defaultBinding: `${modifierKey} + R`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlInsertValueHint]: {
    key: ShortcutAction.SqlInsertValueHint,
    label: 'setting.shortcut.sqlInsertValueHint',
    action: ShortcutAction.SqlInsertValueHint,
    defaultBinding: `${modifierKey} + P`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.SqlOpenEditorSetting]: {
    key: ShortcutAction.SqlOpenEditorSetting,
    label: 'setting.shortcut.sqlOpenEditorSetting',
    action: ShortcutAction.SqlOpenEditorSetting,
    defaultBinding: `${modifierKey} + Shift + ,`,
    scope: ShortcutScope.SqlEditor,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.ResultSearch]: {
    key: ShortcutAction.ResultSearch,
    label: 'setting.shortcut.resultSearch',
    action: ShortcutAction.ResultSearch,
    defaultBinding: `${modifierKey} + F`,
    scope: ShortcutScope.ResultSet,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.ResultSubmit]: {
    key: ShortcutAction.ResultSubmit,
    label: 'setting.shortcut.resultSubmit',
    action: ShortcutAction.ResultSubmit,
    defaultBinding: `${modifierKey} + S`,
    scope: ShortcutScope.ResultSet,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.ResultRefresh]: {
    key: ShortcutAction.ResultRefresh,
    label: 'setting.shortcut.resultRefresh',
    action: ShortcutAction.ResultRefresh,
    defaultBinding: `${modifierKey} + R`,
    scope: ShortcutScope.ResultSet,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.TableCopy]: {
    key: ShortcutAction.TableCopy,
    label: 'setting.shortcut.tableCopy',
    action: ShortcutAction.TableCopy,
    defaultBinding: `${modifierKey} + C`,
    scope: ShortcutScope.Table,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.TablePaste]: {
    key: ShortcutAction.TablePaste,
    label: 'setting.shortcut.tablePaste',
    action: ShortcutAction.TablePaste,
    defaultBinding: `${modifierKey} + V`,
    scope: ShortcutScope.Table,
    allowInEditable: true,
    canModify: true,
  },
  [ShortcutAction.TableSelectAll]: {
    key: ShortcutAction.TableSelectAll,
    label: 'setting.shortcut.tableSelectAll',
    action: ShortcutAction.TableSelectAll,
    defaultBinding: `${modifierKey} + A`,
    scope: ShortcutScope.Table,
    allowInEditable: true,
    canModify: true,
  },
};

const MODIFIER_ORDER = isMac ? ['meta', 'ctrl', 'alt', 'shift'] : ['ctrl', 'meta', 'alt', 'shift'];
const MODIFIER_KEYS = new Set(['control', 'ctrl', 'meta', 'cmd', 'command', '⌘', 'alt', 'option', '⌥', 'shift', '⇧']);
const SINGLE_KEY_SHORTCUTS = new Set([
  'enter',
  'escape',
  'tab',
  'backspace',
  'delete',
  'f1',
  'f2',
  'f3',
  'f4',
  'f5',
  'f6',
  'f7',
  'f8',
  'f9',
  'f10',
  'f11',
  'f12',
]);
const NON_PRINTABLE_KEY_LABELS: Record<string, string> = {
  ' ': 'Space',
  spacebar: 'Space',
  escape: 'Escape',
  esc: 'Escape',
  enter: 'Enter',
  return: 'Enter',
  tab: 'Tab',
  backspace: 'Backspace',
  delete: 'Delete',
  arrowup: 'ArrowUp',
  up: 'ArrowUp',
  arrowdown: 'ArrowDown',
  down: 'ArrowDown',
  arrowleft: 'ArrowLeft',
  left: 'ArrowLeft',
  arrowright: 'ArrowRight',
  right: 'ArrowRight',
};

const normalizeModifierToken = (key: string): string | null => {
  const lowerKey = key.toLowerCase();
  if (['meta', 'cmd', 'command', '⌘'].includes(lowerKey)) {
    return 'meta';
  }
  if (['control', 'ctrl'].includes(lowerKey)) {
    return 'ctrl';
  }
  if (['alt', 'option', '⌥'].includes(lowerKey)) {
    return 'alt';
  }
  if (['shift', '⇧'].includes(lowerKey)) {
    return 'shift';
  }
  return null;
};

const normalizeMainKey = (key: string): string => {
  const trimmed = key.trim();
  const lowerKey = trimmed.toLowerCase();
  if (NON_PRINTABLE_KEY_LABELS[lowerKey]) {
    return NON_PRINTABLE_KEY_LABELS[lowerKey];
  }
  if (trimmed.length === 1) {
    return trimmed.toUpperCase();
  }
  return trimmed.charAt(0).toUpperCase() + trimmed.slice(1);
};

const getShortcutKeyFromEvent = (event: KeyboardEvent | React.KeyboardEvent): string => {
  if (!event.code) {
    return event.key;
  }

  if (/^Key[A-Z]$/.test(event.code)) {
    return event.code.replace('Key', '');
  }

  if (/^Digit[0-9]$/.test(event.code)) {
    return event.code.replace('Digit', '');
  }

  const codeMap: Record<string, string> = {
    Comma: ',',
    Period: '.',
    Minus: '-',
    Equal: '=',
    Slash: '/',
    Backslash: '\\',
    BracketLeft: '[',
    BracketRight: ']',
    Semicolon: ';',
    Quote: "'",
    Backquote: '`',
    Space: 'Space',
  };

  return codeMap[event.code] || event.key;
};

export const normalizeShortcutBinding = (binding?: string | null): string | null => {
  if (!binding) {
    return null;
  }

  const modifiers = new Set<string>();
  const mainKeys: string[] = [];

  binding
    .split('+')
    .map((key) => key.trim())
    .filter(Boolean)
    .forEach((key) => {
      const modifier = normalizeModifierToken(key);
      if (modifier) {
        modifiers.add(modifier);
      } else {
        mainKeys.push(normalizeMainKey(key));
      }
    });

  if (!mainKeys.length) {
    return null;
  }

  const modifierLabels = MODIFIER_ORDER.filter((modifier) => modifiers.has(modifier)).map((modifier) => {
    if (modifier === 'meta') {
      return isMac ? '⌘' : 'Ctrl';
    }
    if (modifier === 'ctrl') {
      return 'Ctrl';
    }
    if (modifier === 'alt') {
      return 'Alt';
    }
    return 'Shift';
  });

  return [...modifierLabels, ...mainKeys].join(' + ');
};

export const getEventShortcutBinding = (event: KeyboardEvent | React.KeyboardEvent): string | null => {
  const key = getShortcutKeyFromEvent(event);
  if (!key || MODIFIER_KEYS.has(key.toLowerCase())) {
    return null;
  }

  const keys: string[] = [];
  if (event.metaKey) {
    keys.push('meta');
  } else if (event.ctrlKey) {
    keys.push('ctrl');
  }
  if (event.altKey) {
    keys.push('alt');
  }
  if (event.shiftKey) {
    keys.push('shift');
  }
  keys.push(key);

  return normalizeShortcutBinding(keys.join(' + '));
};

export const isShortcutCaptureAllowed = (pressedKeys: Iterable<string>): boolean => {
  const keys = Array.from(pressedKeys, (key) => key.toLowerCase());
  const hasModifierKey = keys.some((key) => MODIFIER_KEYS.has(key));
  const isAllowedSingleKey = keys.length === 1 && SINGLE_KEY_SHORTCUTS.has(keys[0]);
  return isAllowedSingleKey || (keys.length > 1 && hasModifierKey);
};

export const isShortcutBindingEqual = (left?: string | null, right?: string | null): boolean =>
  normalizeShortcutBinding(left) === normalizeShortcutBinding(right);

export const isShortcutEventMatch = (event: KeyboardEvent | React.KeyboardEvent, binding?: string | null): boolean => {
  const normalizedBinding = normalizeShortcutBinding(binding);
  if (!normalizedBinding) {
    return false;
  }
  return getEventShortcutBinding(event) === normalizedBinding;
};

export const getEffectiveShortcutConfig = (
  action: ShortcutAction,
  overrides: ShortcutOverrides = {},
): EffectiveShortcutConfig => {
  const defaultConfig = DEFAULT_SHORTCUT_CONFIG[action];
  const override = overrides?.[action];
  const normalizedOverride = normalizeShortcutBinding(override?.binding);
  const binding = override ? normalizedOverride : defaultConfig.defaultBinding;

  return {
    ...defaultConfig,
    binding,
    disabled: binding === null,
    isDefault: !override || isShortcutBindingEqual(binding, defaultConfig.defaultBinding),
  };
};

export const getEffectiveShortcutConfigMap = (
  overrides: ShortcutOverrides = {},
): Record<ShortcutAction, EffectiveShortcutConfig> =>
  Object.values(ShortcutAction).reduce((configMap, action) => {
    configMap[action] = getEffectiveShortcutConfig(action, overrides);
    return configMap;
  }, {} as Record<ShortcutAction, EffectiveShortcutConfig>);

export const getShortcutOverrideValue = (
  action: ShortcutAction,
  binding: string | null,
): ShortcutOverride | undefined => {
  const normalizedBinding = normalizeShortcutBinding(binding);
  const defaultBinding = DEFAULT_SHORTCUT_CONFIG[action]?.defaultBinding;
  if (isShortcutBindingEqual(normalizedBinding, defaultBinding)) {
    return undefined;
  }
  return {
    binding: normalizedBinding,
  };
};

export const getShortcutLabel = (binding?: string | null): string => {
  const normalizedBinding = normalizeShortcutBinding(binding);
  if (!normalizedBinding) {
    return '';
  }

  return normalizedBinding
    .split(' + ')
    .map((key) => {
      if (key === 'Shift') {
        return isMac ? '⇧' : 'Shift';
      }
      if (key === 'Alt') {
        return isMac ? '⌥' : 'Alt';
      }
      if (key === 'Enter') {
        return '↵';
      }
      return key;
    })
    .join(isMac ? '' : '+');
};

export const shortcutBindingToMonacoKeybinding = (binding: string | null | undefined, monacoInstance: any) => {
  const normalizedBinding = normalizeShortcutBinding(binding);
  if (!normalizedBinding) {
    return null;
  }

  let keybinding = 0;
  let keyCode = 0;

  normalizedBinding.split(' + ').forEach((key) => {
    if (key === '⌘' || key === 'Ctrl') {
      keybinding |= monacoInstance.KeyMod.CtrlCmd;
      return;
    }
    if (key === 'Shift') {
      keybinding |= monacoInstance.KeyMod.Shift;
      return;
    }
    if (key === 'Alt') {
      keybinding |= monacoInstance.KeyMod.Alt;
      return;
    }

    if (/^[A-Z]$/.test(key)) {
      keyCode = monacoInstance.KeyCode[`Key${key}`];
    } else if (/^[0-9]$/.test(key)) {
      keyCode = monacoInstance.KeyCode[`Digit${key}`];
    } else {
      const keyCodeMap: Record<string, number> = {
        Enter: monacoInstance.KeyCode.Enter,
        Escape: monacoInstance.KeyCode.Escape,
        Tab: monacoInstance.KeyCode.Tab,
        Space: monacoInstance.KeyCode.Space,
        ',': monacoInstance.KeyCode.Comma,
        '.': monacoInstance.KeyCode.Period,
        '-': monacoInstance.KeyCode.Minus,
        '=': monacoInstance.KeyCode.Equal,
        '/': monacoInstance.KeyCode.Slash,
        '\\': monacoInstance.KeyCode.Backslash,
        '[': monacoInstance.KeyCode.BracketLeft,
        ']': monacoInstance.KeyCode.BracketRight,
        ';': monacoInstance.KeyCode.Semicolon,
        "'": monacoInstance.KeyCode.Quote,
        '`': monacoInstance.KeyCode.Backquote,
      };
      keyCode = keyCodeMap[key] || 0;
    }
  });

  if (!keyCode) {
    return null;
  }

  return keybinding | keyCode;
};
