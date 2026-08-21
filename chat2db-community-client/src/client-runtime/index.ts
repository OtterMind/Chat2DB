import { APP_URL_CONFIG_COMMUNITY } from '@/constants/appConfig';
import { COMMUNITY_IDENTITY, COMMUNITY_WORKSPACE_CONTEXT } from '@/constants/community';
import type { ClientIdentity, ClientWorkspaceContext } from '@/client-context/types';
import type { GlobalAppConfig } from '@/typings/settings';
import { isDesktop } from '@/utils/env';

export interface ClientRuntime {
  runtimeKey: string;
  usesFixedIdentity: boolean;
  usesLocalPersistence: boolean;
  requiresAuthentication: boolean;
  loadAppConfigFromServer: boolean;
  loadSubscriptionFromServer: boolean;
  loadModelOptionsFromServer: boolean;
  enableSpmTracking: boolean;
  enableGoogleAds: boolean;
  enablePricingAutoPopup: boolean;
  enableTeamWorkspace: boolean;
  showAccountCenter: boolean;
  showUpgradeEntry: boolean;
  showDownloadEntry: boolean;
  enableAutoUpdate: boolean;
  showMcpSetting: boolean;
  showNetworkProxySetting: boolean;
  showLicenseSetting: boolean;
  showDashboard: boolean;
  enableDashboardSharing: boolean;
  enableHostedDashboardGeneration: boolean;
  showFeedback: boolean;
  restrictChineseOutsideChina: boolean;
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
  fixedIdentity?: ClientIdentity;
  fixedWorkspaceContext?: ClientWorkspaceContext;
  localAppConfig?: GlobalAppConfig;
  localAppUrlConfig?: typeof APP_URL_CONFIG_COMMUNITY;
}

export const clientRuntime: ClientRuntime = {
  runtimeKey: 'community',
  usesFixedIdentity: true,
  usesLocalPersistence: true,
  requiresAuthentication: false,
  loadAppConfigFromServer: false,
  loadSubscriptionFromServer: false,
  loadModelOptionsFromServer: false,
  enableSpmTracking: false,
  enableGoogleAds: false,
  enablePricingAutoPopup: false,
  enableTeamWorkspace: false,
  showAccountCenter: false,
  showUpgradeEntry: false,
  showDownloadEntry: false,
  enableAutoUpdate: false,
  showMcpSetting: isDesktop,
  showNetworkProxySetting: isDesktop,
  showLicenseSetting: false,
  showDashboard: true,
  enableDashboardSharing: false,
  enableHostedDashboardGeneration: false,
  showFeedback: false,
  restrictChineseOutsideChina: false,
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
  fixedIdentity: COMMUNITY_IDENTITY,
  fixedWorkspaceContext: COMMUNITY_WORKSPACE_CONTEXT,
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

export default clientRuntime;
