import {
  GlobalAppConfig,
  GlobalBaseSettings,
  ShortcutOverrides,
  DataTableSettings,
  TerminalSettings,
} from '@/typings/settings';
import { DEFAULT_BASE_SETTINGS, DEFAULT_APP_CONFIG, DATA_TABLE_SETTINGS } from '@/constants/settings';
import { DEFAULT_EDITOR_SETTINGS } from '@/components/SQLEditor/constants';
import type { EditorSettings } from '@/components/SQLEditor/type';
import { DEFAULT_TERMINAL_SETTINGS } from '@/constants/terminal';

export interface GlobalSettings {
  baseSetting: GlobalBaseSettings;
  appConfig: GlobalAppConfig;
  editorSettings: EditorSettings;
  dataTableSettings: DataTableSettings;
  shortcutOverrides: ShortcutOverrides;
  terminalSettings: TerminalSettings;
}

export interface GlobalSettingState extends GlobalSettings {}

export const initialSettingState: GlobalSettingState = {
  baseSetting: DEFAULT_BASE_SETTINGS,
  appConfig: DEFAULT_APP_CONFIG,
  editorSettings: DEFAULT_EDITOR_SETTINGS,
  dataTableSettings: DATA_TABLE_SETTINGS,
  shortcutOverrides: {},
  terminalSettings: DEFAULT_TERMINAL_SETTINGS,
};
