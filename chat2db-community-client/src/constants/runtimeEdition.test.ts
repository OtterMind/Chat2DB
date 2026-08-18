import assert from 'node:assert/strict';

Object.assign(globalThis, {
  __RUNTIME_ENV__: 'offline',
  __ENV__: 'test',
  __APP_VERSION__: '5.3.0',
  __APP_NAME__: 'chat2db-local',
  window: { javaQuery: {} },
});

async function main() {
  const runtimeEditionModule = await import('./runtimeEdition');
  const runtimeEditionConfig =
    runtimeEditionModule.runtimeEditionConfig ||
    (
      runtimeEditionModule as typeof runtimeEditionModule & {
        default?: typeof runtimeEditionModule;
      }
    ).default?.runtimeEditionConfig;

  assert.ok(runtimeEditionConfig);
  assert.deepEqual(
    {
      clientStorageEdition: runtimeEditionConfig.clientStorageEdition,
      globalStoreName: runtimeEditionConfig.globalStoreName,
      userStoreName: runtimeEditionConfig.userStoreName,
      orgStoreName: runtimeEditionConfig.orgStoreName,
      workspaceStoreName: runtimeEditionConfig.workspaceStoreName,
      aiStoreName: runtimeEditionConfig.aiStoreName,
      treeStoreName: runtimeEditionConfig.treeStoreName,
      localStorageVersionKey: runtimeEditionConfig.localStorageVersionKey,
      aiModelConfigStorageKey: runtimeEditionConfig.aiModelConfigStorageKey,
      loginRedirectStorageKey: runtimeEditionConfig.loginRedirectStorageKey,
      desktopResponseHeaderStorageKey: runtimeEditionConfig.desktopResponseHeaderStorageKey,
      sidebarExpandedStorageKey: runtimeEditionConfig.sidebarExpandedStorageKey,
      notificationPopupStorageKey: runtimeEditionConfig.notificationPopupStorageKey,
      contentDiffDisabledSurfacesStorageKey: runtimeEditionConfig.contentDiffDisabledSurfacesStorageKey,
      googleAdsSignupPendingStorageKey: runtimeEditionConfig.googleAdsSignupPendingStorageKey,
      googleAdsSignupOnceStorageKeyPrefix: runtimeEditionConfig.googleAdsSignupOnceStorageKeyPrefix,
      googleAdsPurchaseOnceStorageKeyPrefix: runtimeEditionConfig.googleAdsPurchaseOnceStorageKeyPrefix,
      pricingAutoPopupStorageKey: runtimeEditionConfig.pricingAutoPopupStorageKey,
      dailyPopupStorageKeyPrefix: runtimeEditionConfig.dailyPopupStorageKeyPrefix,
      currentWorkspaceDatabaseStorageKey: runtimeEditionConfig.currentWorkspaceDatabaseStorageKey,
      currentConnectionStorageKey: runtimeEditionConfig.currentConnectionStorageKey,
      activeConsoleIdStorageKey: runtimeEditionConfig.activeConsoleIdStorageKey,
      currentPageStorageKey: runtimeEditionConfig.currentPageStorageKey,
      indexedDbKeyPrefix: runtimeEditionConfig.indexedDbKeyPrefix,
      dexieDatabaseName: runtimeEditionConfig.dexieDatabaseName,
      localSqlDirectoryPathStorageKey: runtimeEditionConfig.localSqlDirectoryPathStorageKey,
      localSqlDirectoryPathsStorageKey: runtimeEditionConfig.localSqlDirectoryPathsStorageKey,
    },
    {
      clientStorageEdition: 'enterprise',
      globalStoreName: 'Chat2DB_Global_Store',
      userStoreName: 'Chat2DB_User_Store',
      orgStoreName: 'Chat2DB_Org_Store',
      workspaceStoreName: 'Chat2DB_Workspace_Store',
      aiStoreName: 'Chat2DB_AI_Store',
      treeStoreName: 'Chat2DB_Tree_Store',
      localStorageVersionKey: 'app-local-storage-versions',
      aiModelConfigStorageKey: 'chat2db_v3_model_configs',
      loginRedirectStorageKey: 'loginRedirectUrl',
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
      indexedDbKeyPrefix: '',
      dexieDatabaseName: 'chat2db_database',
      localSqlDirectoryPathStorageKey: 'chat2db.localSqlFileTree.rootPath',
      localSqlDirectoryPathsStorageKey: 'chat2db.localSqlFileTree.rootPaths',
    },
  );

  console.log('Runtime edition storage contract tests passed');
}

void main();
