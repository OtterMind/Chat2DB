import { ColorProps, ThemeAppearance } from '@chat2db/ui';
import { LangType, UpdatedStatus } from '@/constants/settings';
import { CountryItem } from '@/typings/enterprise/user';

export interface GlobalBaseSettings {
  appearance: ThemeAppearance;
  primaryColor?: ColorProps;
  neutralColor?: ColorProps;
  language: LangType;
  customFont?: string;
  customFontSize?: number;
  defaultPageSize: number;
  enableMcp?: boolean;
}

// Server configuration
export interface ServiceAppConfig {
  /**
   *Country list
   */
  countries: CountryItem[] | null;
}

export interface GlobalAppConfig extends ServiceAppConfig {
  /**
   * Current version
   */
  version: string;
  /**
   * Current country
   */
  curCountry: CountryItem | null;
  /**
   * appUrl
   */
  appUrl: string | null;
  /**
   *  gatewayUrl
   */
  gatewayUrl: string | null;
  /**
   * Whether it is China
   */
  isCN: boolean;
  /**
   * Are you ready?
   */
  isReady: boolean;
}

export interface IHotUpdateConfig {
  /**
   * Do you want to remind me?
   */
  remindMe: boolean;
  /**
   * Whether to download automatically
   */
  autoDownload: boolean;
  /**
   * Whether to install automatically
   */
  autoInstall: boolean;
  /**
   * Whether prerelease versions participate in update checks
   */
  receiveBeta: boolean;
}

export type { ShortcutOverride, ShortcutOverrides } from '@/constants/shortcut';

export interface IUpdateDetail {
  status?: UpdatedStatus; // update status
  progress?: number; // update progress
  version?: string; // Latest version number
}

export interface IUpdatePreferences {
  saved: boolean;
  receiveBeta: boolean;
}

export interface IUpdateRecoveryStatus {
  failed: boolean;
  fromVersion: string;
  toVersion: string;
}

export type McpRuntimeState = 'UNKNOWN' | 'STARTING' | 'RUNNING' | 'STOPPED' | 'FAILED';

export interface McpStatus {
  operationId: string;
  configuredEnabled: boolean;
  appliedEnabled: boolean;
  runtimeState: McpRuntimeState;
  restartRequired: boolean;
  failureMessage?: string;
}

export interface McpRestartResult {
  operationId: string;
  accepted: boolean;
}

export interface DataTableSettings {
  selectionMetrics?: [SelectionMetricId, SelectionMetricId, SelectionMetricId];
  showFieldType?: boolean;
  showFieldComment?: boolean;
}

export type TerminalShellId = 'system' | 'bash' | 'zsh' | 'pwsh' | 'powershell' | 'cmd';
export type TerminalThemeId = 'chat2db-dark' | 'one-dark' | 'dracula' | 'solarized-dark' | 'solarized-light';
export type TerminalOpenPosition = 'tab' | 'bottom' | 'right';

export interface TerminalSettings {
  shellId: TerminalShellId;
  themeId: TerminalThemeId;
  openPosition: TerminalOpenPosition;
  confirmBeforeClose: boolean;
}

export type SelectionMetricId =
  | 'none'
  | 'rowCount'
  | 'count'
  | 'sum'
  | 'average'
  | 'minimum'
  | 'maximum'
  | 'nullCount'
  | 'nonNullCount'
  | 'uniqueCount'
  | 'nullPercentage'
  | 'nonNullPercentage'
  | 'uniquePercentage'
  | 'earliest'
  | 'latest';
