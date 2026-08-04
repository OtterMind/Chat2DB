import { ColorProps, ThemeAppearance } from '@chat2db/ui';
import { IAIConfig, IRemainingUse } from './ai';
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

export interface GlobalAISettings {
  /**
   *AI related configuration
   */
  aiConfig: IAIConfig;
  /**
   * Number of remaining uses of AI
   */
  remainingUse?: IRemainingUse;
  /**
   * Whether to join the whitelist
   */
  hasWhite: boolean;
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
}

export type { ShortcutOverride, ShortcutOverrides } from '@/constants/shortcut';

export interface IUpdateDetail {
  status?: UpdatedStatus; // update status
  progress?: number; // update progress
  version?: string; // Latest version number
}

export interface DataTableSettings {
  showComment: boolean;
  selectionMetrics?: [SelectionMetricId, SelectionMetricId, SelectionMetricId];
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
