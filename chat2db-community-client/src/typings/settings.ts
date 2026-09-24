import { ColorProps, ThemeAppearance } from '@chat2db/ui';
import { LangType, UpdatedStatus } from '@/constants/settings';

/** Why an update check ran; forwarded to the desktop usage report. */
export type UpdateCheckTrigger = 'startup' | 'scheduled' | 'manual';

export interface CountryItem {
  code: string;
  name: string;
  appUrl: string;
  current: boolean;
  redirect: boolean;
  gatewayUrl: string;
}

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

export type SqlxPlatform = 'mac' | 'windows' | 'linux';

export type SqlxInstallState = 'notInstalled' | 'installed' | 'conflict' | 'unsupported';

export type SqlxBinarySource = 'chat2db' | 'external';

export type SqlxUpdateState = 'unknown' | 'upToDate' | 'updateAvailable' | 'checkFailed';

export interface SqlxLatestVersion {
  status: SqlxUpdateState;
  version?: string;
  checkedAt?: number;
  error?: string;
}

export type SqlxOperationStep =
  | 'downloading'
  | 'verifying'
  | 'extracting'
  | 'validating'
  | 'installing'
  | 'updating'
  | 'failed';

export interface SqlxOperation {
  operationId: string;
  kind: 'install' | 'update';
  step: SqlxOperationStep;
  percent?: number;
  message?: string;
}

export interface SqlxStatus {
  state: SqlxInstallState;
  platform: SqlxPlatform;
  version?: string;
  path?: string;
  source?: SqlxBinarySource;
  onPath: boolean;
  installDir: string;
  latest: SqlxLatestVersion;
  operation?: SqlxOperation;
  message?: string;
}

export interface SqlxImportSkipped {
  name?: string;
  reason?: string;
  detail?: string;
}

export interface SqlxImportSummary {
  added: number;
  updated: number;
  unchanged: number;
  total: number;
  datasources?: unknown[];
  skipped?: SqlxImportSkipped[];
}

/** How one Chat2DB datasource stands against the local SQLX store. */
export type SqlxDatasourceStateValue = 'imported' | 'ready' | 'unsupported' | 'incomplete';

export interface SqlxDatasourceState {
  id: number;
  state: SqlxDatasourceStateValue;
  /** Skip code explaining a state the user has to fix; mirrors the import report reasons. */
  reason?: string;
  detail?: string;
}
