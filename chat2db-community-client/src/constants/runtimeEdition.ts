import { APP_URL_CONFIG_COMMUNITY, APP_URL_CONFIG_OVERSEAS } from '@/constants/appConfig';
import { COMMUNITY_ORG, COMMUNITY_USER } from '@/constants/community';
import type { IOrganizationVO } from '@/typings/enterprise/organization';
import type { IUserVO } from '@/typings/enterprise/user';
import type { GlobalAppConfig } from '@/typings/settings';
import { RUNTIME_ENV, isCommunityEnv, isDesktop, isDesktopEnv, isOfflineEnv } from '@/utils/env';

export type SettingMenuProfile = 'commercial' | 'local' | 'community';

export interface RuntimeEditionConfig {
  mode: string;
  usesFixedIdentity: boolean;
  localPersistence: boolean;
  commercialAccount: boolean;
  remoteAppConfig: boolean;
  remoteSubscription: boolean;
  remoteAiModelOptions: boolean;
  aiDataCollection: boolean;
  spmTracking: boolean;
  googleAds: boolean;
  pricingAutoPopup: boolean;
  teamWorkspace: boolean;
  accountCenter: boolean;
  upgradeEntry: boolean;
  downloadEntry: boolean;
  autoUpdate: boolean;
  mcpSetting: boolean;
  networkProxySetting: boolean;
  licenseSetting: boolean;
  dashboardEntry: boolean;
  dashboardShare: boolean;
  dashboardHostedAiGenerate: boolean;
  feedbackEntry: boolean;
  languageRegionRestricted: boolean;
  settingMenuProfile: SettingMenuProfile;
  globalStoreName: string;
  userStoreName: string;
  orgStoreName: string;
  workspaceStoreName: string;
  aiStoreName: string;
  treeStoreName: string;
  localStorageVersionKey: string;
  aiModelConfigStorageKey: string;
  loginRedirectStorageKey: string;
  desktopResponseHeaderStorageKey: string;
  sidebarExpandedStorageKey: string;
  notificationPopupStorageKey: string;
  contentDiffDisabledSurfacesStorageKey: string;
  googleAdsSignupPendingStorageKey: string;
  googleAdsSignupOnceStorageKeyPrefix: string;
  googleAdsPurchaseOnceStorageKeyPrefix: string;
  pricingAutoPopupStorageKey: string;
  dailyPopupStorageKeyPrefix: string;
  currentWorkspaceDatabaseStorageKey: string;
  currentConnectionStorageKey: string;
  activeConsoleIdStorageKey: string;
  currentPageStorageKey: string;
  indexedDbKeyPrefix: string;
  dexieDatabaseName: string;
  localSqlDirectoryPathStorageKey: string;
  localSqlDirectoryPathsStorageKey: string;
  fixedUser?: IUserVO;
  fixedOrganization?: IOrganizationVO;
  localAppConfig?: GlobalAppConfig;
  localAppUrlConfig?: typeof APP_URL_CONFIG_OVERSEAS;
}

const commonConfig: RuntimeEditionConfig = {
  mode: __RUNTIME_ENV__,
  usesFixedIdentity: false,
  localPersistence: false,
  commercialAccount: true,
  remoteAppConfig: true,
  remoteSubscription: true,
  remoteAiModelOptions: true,
  aiDataCollection: true,
  spmTracking: true,
  googleAds: true,
  pricingAutoPopup: true,
  teamWorkspace: true,
  accountCenter: true,
  upgradeEntry: true,
  downloadEntry: true,
  autoUpdate: true,
  mcpSetting: isDesktopEnv,
  networkProxySetting: isDesktopEnv,
  licenseSetting: false,
  dashboardEntry: true,
  dashboardShare: true,
  dashboardHostedAiGenerate: true,
  feedbackEntry: true,
  languageRegionRestricted: true,
  settingMenuProfile: 'commercial',
  globalStoreName: 'Chat2DB_Global_Store',
  userStoreName: 'Chat2DB_User_Store',
  orgStoreName: 'Chat2DB_Org_Store',
  workspaceStoreName: 'Chat2DB_Workspace_Store',
  aiStoreName: 'Chat2DB_AI_Store',
  treeStoreName: 'Chat2DB_Tree_Store',
  localStorageVersionKey: 'app-local-storage-versions',
  aiModelConfigStorageKey: 'chat2db_ai_model_configs',
  loginRedirectStorageKey: 'chat2db-login-redirect-url',
  desktopResponseHeaderStorageKey: 'Chat2db',
  sidebarExpandedStorageKey: 'chat2db_sidebar_expanded',
  notificationPopupStorageKey: 'popedNotification',
  contentDiffDisabledSurfacesStorageKey: 'chat2db.contentDiff.disabledSurfaces',
  googleAdsSignupPendingStorageKey: 'gads_signup_pending',
  googleAdsSignupOnceStorageKeyPrefix: 'gads_signup',
  googleAdsPurchaseOnceStorageKeyPrefix: 'gads_purchase',
  pricingAutoPopupStorageKey: 'pricing-auto-popup-dismissed-at',
  dailyPopupStorageKeyPrefix: '',
  currentWorkspaceDatabaseStorageKey: 'current-workspace-database',
  currentConnectionStorageKey: 'cur-connection',
  activeConsoleIdStorageKey: 'active-console-id',
  currentPageStorageKey: 'curPage',
  indexedDbKeyPrefix: 'chat2db',
  dexieDatabaseName: 'chat2db_database',
  localSqlDirectoryPathStorageKey: 'chat2db.localSqlFileTree.rootPath',
  localSqlDirectoryPathsStorageKey: 'chat2db.localSqlFileTree.rootPaths',
};

const localConfig: RuntimeEditionConfig = {
  ...commonConfig,
  mode: RUNTIME_ENV.OFFLINE,
  localPersistence: true,
  remoteSubscription: false,
  remoteAiModelOptions: true,
  teamWorkspace: false,
  accountCenter: false,
  upgradeEntry: false,
  downloadEntry: false,
  autoUpdate: true,
  mcpSetting: isDesktop,
  networkProxySetting: true,
  licenseSetting: true,
  dashboardEntry: true,
  dashboardShare: true,
  dashboardHostedAiGenerate: true,
  feedbackEntry: false,
  settingMenuProfile: 'local',
  globalStoreName: 'Chat2DB_Local_Global_Store',
  userStoreName: 'Chat2DB_Local_User_Store',
  orgStoreName: 'Chat2DB_Local_Org_Store',
  workspaceStoreName: 'Chat2DB_Local_Workspace_Store',
  aiStoreName: 'Chat2DB_Local_AI_Store',
  treeStoreName: 'Chat2DB_Local_Tree_Store',
  localStorageVersionKey: 'app-local-storage-versions-local',
  aiModelConfigStorageKey: 'chat2db_ai_model_configs',
  loginRedirectStorageKey: 'chat2db-local-login-redirect-url',
  desktopResponseHeaderStorageKey: 'Chat2db_Local',
  sidebarExpandedStorageKey: 'chat2db_local_sidebar_expanded',
  notificationPopupStorageKey: 'chat2db-local-popedNotification',
  contentDiffDisabledSurfacesStorageKey: 'chat2db.local.contentDiff.disabledSurfaces',
  googleAdsSignupPendingStorageKey: 'gads_signup_pending_local',
  googleAdsSignupOnceStorageKeyPrefix: 'gads_signup_local',
  googleAdsPurchaseOnceStorageKeyPrefix: 'gads_purchase_local',
  pricingAutoPopupStorageKey: 'pricing-auto-popup-dismissed-at-local',
  dailyPopupStorageKeyPrefix: 'chat2db-local-popup',
  currentWorkspaceDatabaseStorageKey: 'chat2db-local-current-workspace-database',
  currentConnectionStorageKey: 'chat2db-local-cur-connection',
  activeConsoleIdStorageKey: 'chat2db-local-active-console-id',
  currentPageStorageKey: 'chat2db-local-curPage',
  indexedDbKeyPrefix: 'chat2db_local',
  dexieDatabaseName: 'chat2db_local_database',
  localSqlDirectoryPathStorageKey: 'chat2db.local.localSqlFileTree.rootPath',
  localSqlDirectoryPathsStorageKey: 'chat2db.local.localSqlFileTree.rootPaths',
};

const communityConfig: RuntimeEditionConfig = {
  ...commonConfig,
  mode: RUNTIME_ENV.COMMUNITY,
  usesFixedIdentity: true,
  localPersistence: true,
  commercialAccount: false,
  remoteAppConfig: false,
  remoteSubscription: false,
  remoteAiModelOptions: false,
  aiDataCollection: false,
  spmTracking: false,
  googleAds: false,
  pricingAutoPopup: false,
  teamWorkspace: false,
  accountCenter: false,
  upgradeEntry: false,
  downloadEntry: false,
  autoUpdate: true,
  mcpSetting: isDesktop,
  networkProxySetting: isDesktop,
  licenseSetting: false,
  dashboardEntry: true,
  dashboardShare: false,
  dashboardHostedAiGenerate: false,
  feedbackEntry: false,
  languageRegionRestricted: false,
  settingMenuProfile: 'community',
  globalStoreName: 'Chat2DB_Community_Global_Store',
  userStoreName: 'Chat2DB_Community_User_Store',
  orgStoreName: 'Chat2DB_Community_Org_Store',
  workspaceStoreName: 'Chat2DB_Community_Workspace_Store',
  aiStoreName: 'Chat2DB_Community_AI_Store',
  treeStoreName: 'Chat2DB_Community_Tree_Store',
  localStorageVersionKey: 'app-local-storage-versions-community',
  aiModelConfigStorageKey: 'chat2db_community_ai_model_configs',
  loginRedirectStorageKey: 'chat2db-community-login-redirect-url',
  desktopResponseHeaderStorageKey: 'Chat2db_Community',
  sidebarExpandedStorageKey: 'chat2db_community_sidebar_expanded',
  notificationPopupStorageKey: 'chat2db-community-popedNotification',
  contentDiffDisabledSurfacesStorageKey: 'chat2db.community.contentDiff.disabledSurfaces',
  googleAdsSignupPendingStorageKey: 'gads_signup_pending_community',
  googleAdsSignupOnceStorageKeyPrefix: 'gads_signup_community',
  googleAdsPurchaseOnceStorageKeyPrefix: 'gads_purchase_community',
  pricingAutoPopupStorageKey: 'pricing-auto-popup-dismissed-at-community',
  dailyPopupStorageKeyPrefix: 'chat2db-community-popup',
  currentWorkspaceDatabaseStorageKey: 'chat2db-community-current-workspace-database',
  currentConnectionStorageKey: 'chat2db-community-cur-connection',
  activeConsoleIdStorageKey: 'chat2db-community-active-console-id',
  currentPageStorageKey: 'chat2db-community-curPage',
  indexedDbKeyPrefix: 'chat2db_community',
  dexieDatabaseName: 'chat2db_community_database',
  localSqlDirectoryPathStorageKey: 'chat2db.community.localSqlFileTree.rootPath',
  localSqlDirectoryPathsStorageKey: 'chat2db.community.localSqlFileTree.rootPaths',
  fixedUser: COMMUNITY_USER,
  fixedOrganization: COMMUNITY_ORG,
  localAppConfig: {
    version: __APP_VERSION__,
    countries: [],
    gatewayUrl: null,
    curCountry: null,
    isCN: false,
    isReady: true,
    appUrl: '',
  },
  localAppUrlConfig: APP_URL_CONFIG_COMMUNITY,
};

export const runtimeEditionConfig: RuntimeEditionConfig = isCommunityEnv
  ? communityConfig
  : isOfflineEnv
  ? localConfig
  : commonConfig;
