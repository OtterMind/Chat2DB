import assert from 'node:assert/strict';

const globalObject = globalThis as unknown as {
  __RUNTIME_ENV__?: string;
  __ENV__?: string;
  __APP_NAME__?: string;
  __APP_VERSION__?: string;
  window?: { javaQuery?: () => number };
  location?: { search: string };
};

const originalRuntimeEnvironment = globalObject.__RUNTIME_ENV__;
const originalEnvironment = globalObject.__ENV__;
const originalAppName = globalObject.__APP_NAME__;
const originalAppVersion = globalObject.__APP_VERSION__;
const originalWindow = globalObject.window;
const originalNavigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
const originalLocationDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'location');

globalObject.__RUNTIME_ENV__ = 'desktop';
globalObject.__ENV__ = 'test';
globalObject.__APP_NAME__ = 'chat2db-pro-test';
globalObject.__APP_VERSION__ = '5.3.3';
globalObject.window = { javaQuery: () => 1 };
Object.defineProperty(globalThis, 'navigator', {
  value: { userAgent: 'Mac', app_language: 'en-US', language: 'en-US' },
  configurable: true,
});
Object.defineProperty(globalThis, 'location', {
  value: { search: '' },
  configurable: true,
});

async function run() {
  const [{ createHotUpdateAction }, { default: jcefApi }, { UpdatedStatus }] = await Promise.all([
    import('./action'),
    import('@/jcef'),
    import('@/constants/settings'),
  ]);
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
    let restartCount = 0;
    jcefApi.restartApp = async () => {
      restartCount += 1;
      return true;
    };
    jcefApi.triggerInstallation = async () => {
      throw new Error('bridge rejected installation');
    };
    await state.updateAndRestartApp();
    assert.equal(state.updateDetail.status, UpdatedStatus.UpdateFailed);
    assert.equal(restartCount, 0);

    state.updateDetail = { status: UpdatedStatus.Updated };
    jcefApi.triggerInstallation = async () => false;
    await state.updateAndRestartApp();
    assert.equal(state.updateDetail.status, UpdatedStatus.UpdateFailed);
    assert.equal(restartCount, 0);

    state.updateDetail = { status: UpdatedStatus.Updated };
    jcefApi.triggerInstallation = async () => true;
    await state.updateAndRestartApp();
    assert.equal(state.updateDetail.status, UpdatedStatus.Installing);
    assert.equal(restartCount, 1);

    state.updateDetail = { status: UpdatedStatus.Default };
    jcefApi.appCheckUpdate = async () => {
      throw new Error('check failed');
    };
    assert.equal(await state.handleCheckUpdate(), false);
    assert.equal(state.updateDetail.status, UpdatedStatus.UpdateFailed);

    jcefApi.updatePreferences = async () => {
      throw new Error('preference save failed');
    };
    await state.updateHotUpdateConfig('receiveBeta', true);
    assert.equal(state.hotUpdateConfig.receiveBeta, false);

    jcefApi.updatePreferences = async () => ({ saved: false, receiveBeta: true });
    await state.updateHotUpdateConfig('receiveBeta', true);
    assert.equal(state.hotUpdateConfig.receiveBeta, false);

    jcefApi.updatePreferences = async () => ({ saved: true, receiveBeta: true });
    await state.updateHotUpdateConfig('receiveBeta', true);
    assert.equal(state.hotUpdateConfig.receiveBeta, true);

    state.updateDetail = { status: UpdatedStatus.Installed };
    jcefApi.restartApp = async () => {
      throw new Error('restart failed');
    };
    await state.updateAndRestartApp();
    assert.equal(state.updateDetail.status, UpdatedStatus.UpdateFailed);

    console.log('Hot update action tests passed');
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
    if (originalRuntimeEnvironment === undefined) {
      delete globalObject.__RUNTIME_ENV__;
    } else {
      globalObject.__RUNTIME_ENV__ = originalRuntimeEnvironment;
    }
    if (originalEnvironment === undefined) {
      delete globalObject.__ENV__;
    } else {
      globalObject.__ENV__ = originalEnvironment;
    }
    if (originalAppName === undefined) {
      delete globalObject.__APP_NAME__;
    } else {
      globalObject.__APP_NAME__ = originalAppName;
    }
    if (originalAppVersion === undefined) {
      delete globalObject.__APP_VERSION__;
    } else {
      globalObject.__APP_VERSION__ = originalAppVersion;
    }
    if (originalWindow === undefined) {
      delete globalObject.window;
    } else {
      globalObject.window = originalWindow;
    }
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
