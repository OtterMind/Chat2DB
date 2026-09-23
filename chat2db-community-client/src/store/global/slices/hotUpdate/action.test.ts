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
  value: { userAgent: 'Mac', app_language: 'en-US', language: 'en-US' },
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
  const [{ createHotUpdateAction }, { default: jcefApi }, { UpdatedStatus }] = await Promise.all([
    import('./action'),
    import('@/jcef'),
    import('@/constants/settings'),
  ]);
  const { clientRuntime } = await import('@/client-runtime');
  assert.equal(clientRuntime.supportsBetaUpdates, true);
  const originalApi = {
    appCheckUpdate: jcefApi.appCheckUpdate,
    triggerInstallation: jcefApi.triggerInstallation,
    restartApp: jcefApi.restartApp,
    updatePreferences: jcefApi.updatePreferences,
  };
  const state: any = {
    updateDetail: { status: UpdatedStatus.Updated },
    offlineActivation: false,
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
    let lastCheckRequest: any;
    jcefApi.appCheckUpdate = async (request?: any) => {
      desktopBridgeCalls += 1;
      lastCheckRequest = request;
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
    // The check only reports whether an update exists, so the installation that is running keeps
    // its own status: the test starts the availability part from a neutral state.
    state.updateDetail.status = UpdatedStatus.Default;
    assert.equal(await state.handleCheckUpdate('startup'), true);
    assert.deepEqual(lastCheckRequest, { trigger: 'startup', offlineActivation: false });

    state.offlineActivation = true;
    assert.equal(await state.handleCheckUpdate('scheduled'), true);
    assert.deepEqual(lastCheckRequest, { trigger: 'scheduled', offlineActivation: true });
    state.setOfflineActivation(false);

    await state.syncUpdatePreferences();
    await state.updateHotUpdateConfig('receiveBeta', true);

    assert.equal(desktopBridgeCalls, 6);
    assert.equal(state.updateDetail.status, UpdatedStatus.Available);
    assert.equal(state.hotUpdateConfig.receiveBeta, true);

    await state.updateHotUpdateConfig('remindMe', false);
    assert.equal(state.hotUpdateConfig.remindMe, false);

    let restarts = 0;
    jcefApi.restartApp = async () => {
      restarts += 1;
      return true;
    };
    state.updateDetail.status = UpdatedStatus.Updated;
    jcefApi.triggerInstallation = async () => false;
    await state.updateAndRestartApp();
    assert.equal(restarts, 0);
    assert.equal(state.updateDetail.status, UpdatedStatus.UpdateFailed);

    // A check must never erase the progress the desktop updater already reported.
    let checkStatus = UpdatedStatus.NotAvailable;
    let checkVersion = '5.3.1';
    jcefApi.appCheckUpdate = async () => ({ status: checkStatus, version: checkVersion }) as any;

    state.updateDetail.status = UpdatedStatus.Updating;
    assert.equal(await state.handleCheckUpdate('scheduled'), true);
    assert.equal(state.updateDetail.status, UpdatedStatus.Updating);

    state.updateDetail.status = UpdatedStatus.Installing;
    assert.equal(await state.handleCheckUpdate('scheduled'), true);
    assert.equal(state.updateDetail.status, UpdatedStatus.Installing);

    state.updateDetail.status = UpdatedStatus.Updated;
    assert.equal(await state.handleCheckUpdate('scheduled'), true);
    assert.equal(state.updateDetail.status, UpdatedStatus.Updated);

    // A failed check must not report a failed download or installation either.
    jcefApi.appCheckUpdate = async () => {
      throw new Error('desktop bridge failed');
    };
    assert.equal(await state.handleCheckUpdate('scheduled'), true);
    assert.equal(state.updateDetail.status, UpdatedStatus.Updated);

    // A check that cannot reach the update source reports a failure, not "no update".
    state.updateDetail.status = UpdatedStatus.Default;
    assert.equal(await state.handleCheckUpdate('manual'), false);
    assert.equal(state.updateDetail.status, UpdatedStatus.UpdateFailed);

    jcefApi.appCheckUpdate = async () => ({ status: checkStatus, version: checkVersion }) as any;

    // A downloaded update is offered to the user instead of being reported as "no update".
    checkStatus = UpdatedStatus.Updated;
    state.updateDetail.status = UpdatedStatus.Default;
    assert.equal(await state.handleCheckUpdate('manual'), true);
    assert.equal(state.updateDetail.status, UpdatedStatus.Updated);

    // A later release can still be discovered after the prepared one was discarded.
    checkStatus = UpdatedStatus.Available;
    checkVersion = '5.3.2';
    state.updateDetail.status = UpdatedStatus.Updated;
    assert.equal(await state.handleCheckUpdate('manual'), true);
    assert.equal(state.updateDetail.status, UpdatedStatus.Available);
    assert.equal(state.updateDetail.version, '5.3.2');

    // A discovered update is still reported after a failed download.
    state.updateDetail.status = UpdatedStatus.UpdateFailed;
    assert.equal(await state.handleCheckUpdate('manual'), true);
    assert.equal(state.updateDetail.status, UpdatedStatus.Available);

    // Overlapping triggers share the running desktop check instead of queueing more.
    let checks = 0;
    let resolveCheck: (value: any) => void = () => undefined;
    jcefApi.appCheckUpdate = async () => {
      checks += 1;
      return new Promise((resolve) => {
        resolveCheck = resolve;
      });
    };
    const first = state.handleCheckUpdate('manual');
    const second = state.handleCheckUpdate('manual');
    const scheduled = state.handleCheckUpdate('scheduled');
    assert.equal(checks, 1);
    resolveCheck({ status: UpdatedStatus.Available, version: '5.3.3' });
    assert.deepEqual(await Promise.all([first, second, scheduled]), [true, true, true]);
    assert.equal(checks, 1);
    assert.equal(state.updateDetail.version, '5.3.3');

    console.log('Community hot update integration tests passed');
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
