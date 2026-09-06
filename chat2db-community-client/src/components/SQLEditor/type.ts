import { WorkspaceTabType } from '@/constants/workspace';
import { editorFontFamily, editorThemes } from './config';
import * as monaco from 'monaco-editor';

// Export theme keys as TypeScript keys.
export type IEditorTheme = keyof typeof editorThemes;

export type IEditorFontFamily = keyof typeof editorFontFamily;

export type SqlCompletionAcceptKey = 'enter' | 'tab';

export interface EditorSettings {
  theme: IEditorTheme;
  lightTheme: IEditorTheme;
  darkTheme: IEditorTheme;
  darkDimmedTheme: IEditorTheme;
  fontSize: number;
  fontFamily: IEditorFontFamily;
  customFontFamily: string;
  lineHeight: number;
  language: string;
  lineNumbers: 'on' | 'off';
  minimap?: monaco.editor.IEditorMinimapOptions;
  wordWrap?: 'off' | 'on' | 'wordWrapColumn' | 'bounded';
  folding?: boolean;
  completion?: string[];
  keywordCase?: boolean;
  errorContinue?: boolean;
  tableDDLTriggerMode?: 'hover' | 'click';
  completionAcceptKey?: SqlCompletionAcceptKey;
  renderLineHighlight?: monaco.editor.IEditorOptions['renderLineHighlight'];
  stickyScroll?: monaco.editor.IEditorStickyScrollOptions;
  confirmBeforeClose?: boolean;
}

export interface IRange {
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
}

/** Action bar action. */
export enum SQLOptType {
  NL_2_SQL = 'NL_2_SQL',
  SQL_EXPLAIN = 'SQL_EXPLAIN',
  SQL_OPTIMIZER = 'SQL_OPTIMIZER',

  COPY = 'COPY',
  PASTE = 'PASTE',
  PASTE_AS_SQL_IN_VALUES = 'PASTE_AS_SQL_IN_VALUES',
  CUT = 'CUT',
  CASE_CONVERT = 'CASE_CONVERT',

  EXECUTE_SQL = 'EXECUTE_SQL',
  EXECUTE_ROUTINE = 'EXECUTE_ROUTINE',
  EXECUTE_TABLE = 'EXECUTE_TABLE',
  EXECUTE_SINGLE_SQL = 'EXECUTE_SINGLE_SQL',
  EXECUTE_SHORTCUT_SQL = 'EXECUTE_SHORTCUT_SQL',
  APPLY_ROUTINE_DDL = 'APPLY_ROUTINE_DDL',
  REFRESH_ROUTINE_DDL = 'REFRESH_ROUTINE_DDL',
  REVERT_ROUTINE_DDL = 'REVERT_ROUTINE_DDL',
  FORMAT_SQL = 'FORMAT_SQL',
  TOGGLE_LINE_COMMENT = 'TOGGLE_LINE_COMMENT',
  TOGGLE_BLOCK_COMMENT = 'TOGGLE_BLOCK_COMMENT',
  EXPLAIN_SQL = 'EXPLAIN_SQL',
  EXPLAIN_JSON = 'EXPLAIN_JSON',
  EXPLAIN_ANALYZE = 'EXPLAIN_ANALYZE',
  VIEW_TABLE_DDL = 'VIEW_TABLE_DDL',
  EDIT_TABLE = 'EDIT_TABLE',

  SAVE_SQL = 'SAVE_SQL',
  SAVE_FILE = 'SAVE_FILE',
  SAVE_FILE_TO_DESKTOP = 'SAVE_FILE_TO_DESKTOP',

  OPEN_CONTENT_DIFF = 'OPEN_CONTENT_DIFF',
  OPEN_SETTINGS = 'OPEN_SETTINGS',
}

export type EditorType =
  | WorkspaceTabType.CONSOLE
  | WorkspaceTabType.FUNCTION
  | WorkspaceTabType.PROCEDURE
  | WorkspaceTabType.TRIGGER
  | WorkspaceTabType.VIEW
  | WorkspaceTabType.LocalSQLFile;

export type EditorSetValueType = 'cover' | 'end' | 'start' | 'replace' | 'cursor' | 'reset';

export enum ContentDiffKind {
  Added = 'added',
  Deleted = 'deleted',
  Modified = 'modified',
  Equal = 'equal',
}

const contains = <T>(list: readonly T[], value: unknown): value is T => list.includes(value as T);

const CONTENT_DIFF_EDITABLE_DDL_TAB_TYPES = [
  WorkspaceTabType.VIEW,
  WorkspaceTabType.FUNCTION,
  WorkspaceTabType.PROCEDURE,
  WorkspaceTabType.TRIGGER,
] as const;

const CONTENT_DIFF_SAVED_SQL_TAB_TYPES = [WorkspaceTabType.CONSOLE] as const;

const CONTENT_DIFF_LOCAL_SQL_FILE_TAB_TYPES = [WorkspaceTabType.LocalSQLFile] as const;

export const isContentDiffEditableDDLType = (type: unknown) => contains(CONTENT_DIFF_EDITABLE_DDL_TAB_TYPES, type);

export const isContentDiffSavedSQLType = (type: unknown) => contains(CONTENT_DIFF_SAVED_SQL_TAB_TYPES, type);

export const isContentDiffLocalSQLFileType = (type: unknown) => contains(CONTENT_DIFF_LOCAL_SQL_FILE_TAB_TYPES, type);

export enum TIP_TYPE {
  SNIPPET = 'SNIPPET',
  KEYWORD = 'KEYWORD',
  FUNCTION = 'FUNCTION',
  TYPE = 'TYPE',
  DATABASE = 'DATABASE',
  SCHEMA = 'SCHEMA',
  TABLE = 'TABLE',
  COLUMN = 'COLUMN',
  VIEW = 'VIEW',
  PROCEDURE = 'PROCEDURE',
  TRIGGER = 'TRIGGER',
  EVENT = 'EVENT',
  PARAMETER = 'PARAMETER',
  VARIABLE = 'VARIABLE',
  ALIAS = 'ALIAS',
  JOIN_CLAUSE = 'JOIN_CLAUSE',
  ALL_COLUMN = 'ALL_COLUMN',
}

export enum SORT_TEXT {
  TIPS = 'aa',
  SNIPPET = 'a',
  KEYWORD_HIGH_PRIORITY = 'b',
  KEYWORD = 'c',
  ALL_COLUMN = 'd',
  COLUMN = 'e',
  TABLE = 'f',
  DATABASE = 'g',
  SCHEMA = 'h',
  FUNCTION = 'i',
  TYPE = 'j',
  PROCEDURE = 'k',
  TRIGGER = 'l',
  EVENT = 'm',
  VIEW = 'n',
  PARAMETER = 'o',
  JOIN_CLAUSE = 'p',
  ALIAS = 'q',
}

export interface IInBuildKeyword {}
