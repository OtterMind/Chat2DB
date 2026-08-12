import assert from 'node:assert/strict';

void (async () => {
  const { UpdatedStatus } = await import('./status');
  const { createCheckUpdateCoordinator } = await import('./checkCoordinator');
  type IUpdateDetail = {
    status?: (typeof UpdatedStatus)[keyof typeof UpdatedStatus];
    progress?: number;
    version?: string;
    releaseNotes?: string;
  };

  async function testRepeatedChecksShareOneJcefRequestAndReachAvailable() {
    const updates: IUpdateDetail[] = [];
    let requestCount = 0;
    let resolveCheck: (detail: IUpdateDetail) => void = () => undefined;
    const request = new Promise<IUpdateDetail>((resolve) => {
      resolveCheck = resolve;
    });
    const check = createCheckUpdateCoordinator(
      () => {
        requestCount += 1;
        return request;
      },
      (detail) => updates.push(detail),
    );

    const firstCheck = check();
    const secondCheck = check();

    assert.strictEqual(firstCheck, secondCheck);
    assert.equal(requestCount, 1);
    assert.deepEqual(updates, [{ status: UpdatedStatus.Checking }]);

    resolveCheck({ status: UpdatedStatus.Available, version: '5.3.1', releaseNotes: 'Fixes' });
    assert.equal(await firstCheck, true);
    assert.deepEqual(updates, [
      { status: UpdatedStatus.Checking },
      { status: UpdatedStatus.Available, version: '5.3.1', releaseNotes: 'Fixes' },
    ]);
  }

  async function testCheckReachesNotAvailableAndFailureStates() {
    const notAvailableUpdates: IUpdateDetail[] = [];
    const notAvailable = createCheckUpdateCoordinator(
      async () => ({ status: UpdatedStatus.NotAvailable }),
      (detail) => notAvailableUpdates.push(detail),
    );
    assert.equal(await notAvailable(), false);
    assert.deepEqual(notAvailableUpdates, [
      { status: UpdatedStatus.Checking },
      { status: UpdatedStatus.NotAvailable, version: undefined, releaseNotes: undefined },
    ]);

    const failedUpdates: IUpdateDetail[] = [];
    const failed = createCheckUpdateCoordinator(
      async () => Promise.reject(new Error('CDN unavailable')),
      (detail) => failedUpdates.push(detail),
    );
    assert.equal(await failed(), false);
    assert.deepEqual(failedUpdates, [
      { status: UpdatedStatus.Checking },
      { status: UpdatedStatus.UpdateFailed },
    ]);
  }

  async function testRepeatedDownloadAndRestartUseOneJcefRequestEach() {
    const callbacks: Record<string, { onSuccess: (data: string) => void }> = {};
    const requestCounts: Record<string, number> = {};
    Object.assign(globalThis as Record<string, unknown>, {
      __APP_NAME__: 'chat2db-community',
      __APP_VERSION__: '0.0.0-test',
      __RUNTIME_ENV__: 'community',
      __ENV__: 'production',
      navigator: { userAgent: 'node', language: 'en-US', app_language: 'en-US' },
      location: { search: '' },
      window: {
        javaQuery: ({ request, onSuccess }: { request: string; onSuccess: (data: string) => void }) => {
          const command = JSON.parse(request).requestUrl as string;
          requestCounts[command] = (requestCounts[command] || 0) + 1;
          callbacks[command] = { onSuccess };
        },
      },
    });
    const { createHotUpdateAction } = await import('./action');
    const state: {
      updateDetail: IUpdateDetail;
      setUpdateDetail: (detail: IUpdateDetail) => void;
    } = {
      updateDetail: { status: UpdatedStatus.Available },
      setUpdateDetail: (detail) => Object.assign(state.updateDetail, detail),
    };
    const actions = createHotUpdateAction(
      () => undefined,
      () => state as any,
      {} as any,
    );

    const firstDownload = actions.downloadUpdate();
    const secondDownload = actions.downloadUpdate();
    assert.strictEqual(firstDownload, secondDownload);
    assert.equal(requestCounts['trigger-download'], 1);
    callbacks['trigger-download'].onSuccess('true');
    assert.equal(await firstDownload, true);

    state.updateDetail.status = UpdatedStatus.Updated;
    const firstRestart = actions.updateAndRestartApp();
    const secondRestart = actions.updateAndRestartApp();
    assert.equal(requestCounts['trigger-installation'], 1);
    callbacks['trigger-installation'].onSuccess('true');
    await Promise.resolve();
    assert.equal(requestCounts['restart-app'], 1);
    callbacks['restart-app'].onSuccess('{}');
    await Promise.all([firstRestart, secondRestart]);
    assert.equal(state.updateDetail.status, UpdatedStatus.Installed);
  }

  await Promise.all([
    testRepeatedChecksShareOneJcefRequestAndReachAvailable(),
    testCheckReachesNotAvailableAndFailureStates(),
    testRepeatedDownloadAndRestartUseOneJcefRequestEach(),
  ]);
  console.log('Hot update check action tests passed');
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
