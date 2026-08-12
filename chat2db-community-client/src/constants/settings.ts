import { GlobalBaseSettings, GlobalAppConfig, DataTableSettings } from '@/typings/settings';
import { getUserComputerLanguage } from '@/utils';
import { DEFAULT_RESULT_PAGE_SIZE } from './pagination';

export { UpdatedStatus } from '@/store/global/slices/hotUpdate/status';

export enum LangType {
  EN_US = 'en-US',
  ZH_CN = 'zh-CN',
  JA_JP = 'ja-JP',
  ES_ES = 'es-ES',
  KO_KR = 'ko-KR',
}

export const DEFAULT_BASE_SETTINGS: GlobalBaseSettings = {
  appearance: 'dark',
  language: getUserComputerLanguage(),
  customFont: '',
  customFontSize: 13,
  defaultPageSize: DEFAULT_RESULT_PAGE_SIZE,
  enableMcp: false,
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
