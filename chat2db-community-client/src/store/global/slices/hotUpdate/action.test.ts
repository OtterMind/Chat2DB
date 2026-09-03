import assert from 'node:assert/strict';

const globalObject = globalThis as unknown as {
  __RUNTIME_ENV__?: string;
  __ENV__?: string;
  __APP_NAME__?: string;
  __APP_VERSION__?: string;
  __APP_CAPITAL_NAME__?: string;
  __APP_DISPLAY_NAME__?: string;
  __APP_PROTOCOL_SCHEME__?: string;
  window?: { javaQuery?: () => number };
  location?: { search: string };
};

const originalGlobals = {
  runtimeEnvironment: globalObject.__RUNTIME_ENV__,
  environment: globalObject.__ENV__,
  appName: globalObject.__APP_NAME__,
  appVersion: globalObject.__APP_VERSION__,
  appCapitalName: globalObject.__APP_CAPITAL_NAME__,
  appDisplayName: globalObject.__APP_DISPLAY_NAME__,
  appProtocolScheme: globalObject.__APP_PROTOCOL_SCHEME__,
  window: globalObject.window,
};
const originalNavigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
const originalLocationDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'location');

globalObject.__RUNTIME_ENV__ = 'desktop';
globalObject.__ENV__ = 'test';
globalObject.__APP_NAME__ = 'chat2db-community-test';
globalObject.__APP_VERSION__ = '5.3.0';
globalObject.__APP_CAPITAL_NAME__ = 'Chat2DB Community';
globalObject.__APP_DISPLAY_NAME__ = 'Chat2DB Community';
globalObject.__APP_PROTOCOL_SCHEME__ = 'chat2db-community';
globalObject.window = { javaQuery: () => 1 };
Object.defineProperty(globalThis, 'navigator', {
  value: { userAgent: 'Mac', app_language: 'en-US', language: 'en-US', os_type: 'Mac' },
  configurable: true,
});
Object.defineProperty(globalThis, 'location', {
  value: { search: '' },
  configurable: true,
});

function restoreGlobal<T extends keyof typeof globalObject>(key: T, value: (typeof globalObject)[T]) {
  if (value === undefined) {
    delete globalObject[key];
  } else {
    globalObject[key] = value;
  }
}

async function run() {
  const [
    { createHotUpdateAction },
    { default: jcefApi },
    { UpdatedStatus },
    { supportsAutomaticUpdates },
    { Platform },
  ] = await Promise.all([
    import('./action'),
    import('@/jcef'),
    import('@/constants/settings'),
    import('@client-runtime'),
    import('@/constants/os'),
  ]);
  assert.equal(supportsAutomaticUpdates(true, Platform.Mac), true);
  assert.equal(supportsAutomaticUpdates(true, Platform.Windows), false);
  assert.equal(supportsAutomaticUpdates(true, Platform.Linux), false);
  assert.equal(supportsAutomaticUpdates(false, Platform.Mac), false);
  const originalApi = {
    appCheckUpdate: jcefApi.appCheckUpdate,
    triggerInstallation: jcefApi.triggerInstallation,
    restartApp: jcefApi.restartApp,
    updatePreferences: jcefApi.updatePreferences,
  };
  const state: any = {
    updateDetail: { status: UpdatedStatus.Updated },
    hotUpdateConfig: {
      remindMe: true,
      autoDownload: false,
      autoInstall: false,
      receiveBeta: false,
    },
  };
  state.setUpdateDetail = (detail: any) => {
    state.updateDetail = { ...state.updateDetail, ...detail };
  };
  const set = (next: any) => {
    Object.assign(state, typeof next === 'function' ? next(state) : next);
  };
  const get = () => state;
  Object.assign(state, createHotUpdateAction(set as any, get as any, {} as any));

  try {
    let desktopBridgeCalls = 0;
    jcefApi.appCheckUpdate = async () => {
      desktopBridgeCalls += 1;
      return { status: UpdatedStatus.Available, version: '5.3.1' } as any;
    };
    jcefApi.triggerInstallation = async () => {
      desktopBridgeCalls += 1;
      return true;
    };
    jcefApi.restartApp = async () => {
      desktopBridgeCalls += 1;
      return true;
    };
    jcefApi.updatePreferences = async () => {
      desktopBridgeCalls += 1;
      return { saved: true, receiveBeta: true };
    };

    await state.updateAndRestartApp();
    assert.equal(await state.handleCheckUpdate(), true);
    await state.syncUpdatePreferences();
    await state.updateHotUpdateConfig('receiveBeta', true);

    assert.equal(desktopBridgeCalls, 5);
    assert.equal(state.updateDetail.status, UpdatedStatus.Available);
    assert.equal(state.updateDetail.version, '5.3.1');
    assert.equal(state.hotUpdateConfig.receiveBeta, true);

    await state.updateHotUpdateConfig('remindMe', false);
    assert.equal(state.hotUpdateConfig.remindMe, false);

    console.log('Community desktop update flow tests passed');
  } finally {
    Object.assign(jcefApi, originalApi);
  }
}

run()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => {
    restoreGlobal('__RUNTIME_ENV__', originalGlobals.runtimeEnvironment);
    restoreGlobal('__ENV__', originalGlobals.environment);
    restoreGlobal('__APP_NAME__', originalGlobals.appName);
    restoreGlobal('__APP_VERSION__', originalGlobals.appVersion);
    restoreGlobal('__APP_CAPITAL_NAME__', originalGlobals.appCapitalName);
    restoreGlobal('__APP_DISPLAY_NAME__', originalGlobals.appDisplayName);
    restoreGlobal('__APP_PROTOCOL_SCHEME__', originalGlobals.appProtocolScheme);
    restoreGlobal('window', originalGlobals.window);
    if (originalNavigatorDescriptor === undefined) {
      delete (globalThis as { navigator?: Navigator }).navigator;
    } else {
      Object.defineProperty(globalThis, 'navigator', originalNavigatorDescriptor);
    }
    if (originalLocationDescriptor === undefined) {
      delete globalObject.location;
    } else {
      Object.defineProperty(globalThis, 'location', originalLocationDescriptor);
    }
  });
