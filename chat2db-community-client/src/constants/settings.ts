import { GlobalBaseSettings, GlobalAppConfig, DataTableSettings } from '@/typings/settings';
import { getUserComputerLanguage } from '@/utils';
import { DEFAULT_RESULT_PAGE_SIZE } from './pagination';

export enum LangType {
  EN_US = 'en-US',
  ZH_CN = 'zh-CN',
  JA_JP = 'ja-JP',
  ES_ES = 'es-ES',
  KO_KR = 'ko-KR',
}

export enum UpdatedStatus {
  // default
  Default = 'default',
  // There are updates available
  Available = 'available',
  // Not available
  NotAvailable = 'notAvailable',
  // Updating
  Updating = 'updating',
  // Update completed
  Updated = 'updated',
  // Installing
  Installing = 'installing',
  // Installation completed
  Installed = 'installed',
  // Update failed
  UpdateFailed = 'updateFailed',
}

export const DEFAULT_BASE_SETTINGS: GlobalBaseSettings = {
  appearance: 'dark',
  language: getUserComputerLanguage(),
  customFont: '',
  customFontSize: 13,
  defaultPageSize: DEFAULT_RESULT_PAGE_SIZE,
  enableMcp: false,
  enableDshPluginManagement: false,
};

export const DEFAULT_APP_CONFIG: GlobalAppConfig = {
  version: '5.3.0',
  countries: null,
  gatewayUrl: null,
  curCountry: null,
  isCN: false,
  isReady: false,
  appUrl: '',
};

export const DATA_TABLE_SETTINGS: DataTableSettings = {
  selectionMetrics: ['average', 'count', 'sum'],
  showFieldType: true,
  showFieldComment: true,
};
