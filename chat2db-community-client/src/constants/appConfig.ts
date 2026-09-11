import { AppConfig } from '@/typings/appConfig';

export const APP_URL_CONFIG_OVERSEAS = {
  WEBSITE_URL: `https://chat2db.ai`,
  DOWNLOAD_URL: `https://chat2db.ai/download`,
  WEBSITE_PRICING_URL: `https://chat2db.ai/pricing`,
  CHAT2DB_APP_URL: `https://app.chat2db.ai`,
  CHAT2DB_PRICING_URL: `https://app.chat2db.ai/price`,
  DOCS_URL: `https://chat2db.ai/resources/docs/start-guide/getting-started`,
  CHANGE_LOG_URL: `https://chat2db.ai/resources/changelog`,
  SERVICE_AGREEMENT: `https://chat2db.ai/resources/docs/service/service`,
  PRIVACY_POLICY: `https://chat2db.ai/resources/docs/service/privacy`,
  MEMBER_AGREEMENT: `https://chat2db.ai/resources/docs/service/member`,
  CURRENCY_SYMBOL: '$',
};

export const APP_URL_CONFIG_CHINA = {
  WEBSITE_URL: `https://chat2db-ai.com`,
  DOWNLOAD_URL: `https://chat2db-ai.com/download`,
  WEBSITE_PRICING_URL: `https://chat2db-ai.com/pricing`,
  CHAT2DB_APP_URL: `https://app.chat2db-ai.com`,
  CHAT2DB_PRICING_URL: `https://app.chat2db-ai.com/price`,
  DOCS_URL: `https://chat2db-ai.com/resources/docs/start-guide/getting-started`,
  CHANGE_LOG_URL: `https://chat2db-ai.com/resources/changelog`,
  SERVICE_AGREEMENT: `https://chat2db-ai.com/resources/docs/service/service`,
  PRIVACY_POLICY: `https://chat2db-ai.com/resources/docs/service/privacy`,
  MEMBER_AGREEMENT: `https://chat2db-ai.com/resources/docs/service/member`,
  CURRENCY_SYMBOL: '¥',
};

export const APP_URL_CONFIG_COMMUNITY = {
  WEBSITE_URL: `https://chat2db.ai`,
  DOWNLOAD_URL: `https://chat2db.ai/download`,
  WEBSITE_PRICING_URL: '',
  CHAT2DB_APP_URL: '',
  CHAT2DB_PRICING_URL: '',
  DOCS_URL: `https://chat2db.ai/resources/docs/start-guide/getting-started`,
  CHANGE_LOG_URL: `https://chat2db.ai/resources/changelog`,
  SERVICE_AGREEMENT: '',
  PRIVACY_POLICY: `https://chat2db.ai/resources/docs/service/privacy`,
  MEMBER_AGREEMENT: '',
  CURRENCY_SYMBOL: '',
};

export const getAppConfig = (isCN: boolean) => {
  return isCN ? APP_URL_CONFIG_CHINA : APP_URL_CONFIG_OVERSEAS;
};

export const APP_CONFIG: AppConfig = {
  name: __APP_NAME__,
  capitalName: __APP_CAPITAL_NAME__,
  displayName: __APP_DISPLAY_NAME__,
  protocolScheme: __APP_PROTOCOL_SCHEME__,
};
