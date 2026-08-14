import assert from 'node:assert/strict';

void (async () => {
  (globalThis as any).window = globalThis;
  Object.assign(globalThis as Record<string, unknown>, {
    __APP_NAME__: 'chat2db-community',
    __APP_VERSION__: '0.0.0-test',
    __RUNTIME_ENV__: 'community',
    __ENV__: 'production',
    navigator: { userAgent: 'node', language: 'en-US', app_language: 'en-US' },
    location: { search: '' },
    javaQuery: () => undefined,
  });

  const { UpdatedStatus } = await import('./status');
  const { createCheckUpdateCoordinator } = await import('./checkCoordinator');

  async function testRepeatedChecksShareOneJcefRequestAndReachAvailable() {
    const updates: Awaited<ReturnType<typeof createCheckUpdateCoordinator>>[] = [];
    let requestCount = 0;
    let resolveCheck: (detail: any) => void = () => undefined;
    const request = new Promise<any>((resolve) => {
      resolveCheck = resolve;
    });
    const check = createCheckUpdateCoordinator(
      () => {
        requestCount += 1;
        return request;
      },
      (detail) => updates.push(detail as any),
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
      {
        status: UpdatedStatus.Available,
        version: '5.3.1',
        releaseNotes: 'Fixes',
        releasePageUrl: undefined,
        failureStage: undefined,
        failureReason: undefined,
      },
    ]);
  }

  async function testCheckReachesNotAvailableAndFailureStates() {
    const notAvailableUpdates: any[] = [];
    const notAvailable = createCheckUpdateCoordinator(
      async () => ({ status: UpdatedStatus.NotAvailable }),
      (detail) => notAvailableUpdates.push(detail),
    );
    assert.equal(await notAvailable(), false);
    assert.deepEqual(notAvailableUpdates, [
      { status: UpdatedStatus.Checking },
      {
        status: UpdatedStatus.NotAvailable,
        version: undefined,
        releaseNotes: undefined,
        releasePageUrl: undefined,
        failureStage: undefined,
        failureReason: undefined,
      },
    ]);

    const failedUpdates: any[] = [];
    const failed = createCheckUpdateCoordinator(
      async () => Promise.reject(new Error('CDN unavailable')),
      (detail) => failedUpdates.push(detail),
    );
    assert.equal(await failed(), false);
    assert.deepEqual(failedUpdates, [
      { status: UpdatedStatus.Checking },
      { status: UpdatedStatus.UpdateFailed, failureStage: 'CHECK', failureReason: 'UNKNOWN' },
    ]);
  }

  async function testCheckPassesFailureFieldsToDetail() {
    const updates: any[] = [];
    const check = createCheckUpdateCoordinator(
      async () => ({
        status: UpdatedStatus.UpdateFailed,
        version: '5.3.1',
        releaseNotes: 'Fixes',
        releasePageUrl: 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.1',
        failureStage: 'CHECK',
        failureReason: 'NETWORK',
      }),
      (detail) => updates.push(detail),
    );
    assert.equal(await check(), false);
    assert.deepEqual(updates, [
      { status: UpdatedStatus.Checking },
      {
        status: UpdatedStatus.UpdateFailed,
        version: '5.3.1',
        releaseNotes: 'Fixes',
        releasePageUrl: 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.1',
        failureStage: 'CHECK',
        failureReason: 'NETWORK',
      },
    ]);
  }

  async function testRepeatedDownloadAndRestartUseOneJcefRequestEach() {
    const callbacks: Record<string, { onSuccess: (data: string) => void }> = {};
    const requestCounts: Record<string, number> = {};
    (globalThis as any).javaQuery = ({
      request,
      onSuccess,
    }: {
      request: string;
      onSuccess: (data: string) => void;
    }) => {
      const command = JSON.parse(request).requestUrl as string;
      requestCounts[command] = (requestCounts[command] || 0) + 1;
      callbacks[command] = { onSuccess };
    };
    const { createHotUpdateAction } = await import('./action');
    const state: {
      updateDetail: any;
      setUpdateDetail: (detail: any) => void;
    } = {
      updateDetail: { status: UpdatedStatus.Available, version: '5.3.1', releasePageUrl: 'https://example.com/v5.3.1' },
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

  async function testDownloadFailurePreservesRecoveryFields() {
    const callbacks: Record<string, { onSuccess: (data: string) => void }> = {};
    const requestCounts: Record<string, number> = {};
    (globalThis as any).javaQuery = ({
      request,
      onSuccess,
    }: {
      request: string;
      onSuccess: (data: string) => void;
    }) => {
      const command = JSON.parse(request).requestUrl as string;
      requestCounts[command] = (requestCounts[command] || 0) + 1;
      callbacks[command] = { onSuccess };
    };
    const { createHotUpdateAction } = await import('./action');
    const state: {
      updateDetail: any;
      setUpdateDetail: (detail: any) => void;
    } = {
      updateDetail: {
        status: UpdatedStatus.Available,
        version: '5.3.1',
        releasePageUrl: 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.1',
      },
      setUpdateDetail: (detail) => Object.assign(state.updateDetail, detail),
    };
    const actions = createHotUpdateAction(
      () => undefined,
      () => state as any,
      {} as any,
    );

    const download = actions.downloadUpdate();
    callbacks['trigger-download'].onSuccess('false');
    assert.equal(await download, false);
    assert.equal(state.updateDetail.status, UpdatedStatus.UpdateFailed);
    assert.equal(state.updateDetail.failureStage, 'DOWNLOAD');
    assert.equal(state.updateDetail.failureReason, 'UNKNOWN');
    assert.equal(state.updateDetail.version, '5.3.1');
    assert.equal(state.updateDetail.releasePageUrl, 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.1');
  }

  async function testManualRecoveryActionUsesConstantReleasesPageForCheckFailure() {
    const { getManualRecoveryAction, COMMUNITY_GITHUB_RELEASES_URL } = await import('./action');
    const action = getManualRecoveryAction({
      status: UpdatedStatus.UpdateFailed,
      failureStage: 'CHECK',
      failureReason: 'NETWORK',
    });
    assert.deepEqual(action, { url: COMMUNITY_GITHUB_RELEASES_URL });
  }

  async function testManualRecoveryActionUsesValidatedReleasePageUrl() {
    const { getManualRecoveryAction } = await import('./action');
    const action = getManualRecoveryAction({
      status: UpdatedStatus.UpdateFailed,
      version: '5.3.1',
      releasePageUrl: 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.1',
      failureStage: 'DOWNLOAD',
      failureReason: 'NETWORK',
    });
    assert.deepEqual(action, {
      url: 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.1',
      version: '5.3.1',
    });
  }

  async function testManualRecoveryActionDerivesTagUrlWhenReleasePageUrlMissing() {
    const { getManualRecoveryAction, getCommunityGitHubReleaseTagUrl } = await import('./action');
    const action = getManualRecoveryAction({
      status: UpdatedStatus.UpdateFailed,
      version: '5.3.1',
      failureStage: 'DOWNLOAD',
      failureReason: 'CHECKSUM_MISMATCH',
    });
    assert.deepEqual(action, {
      url: getCommunityGitHubReleaseTagUrl('5.3.1'),
      version: '5.3.1',
    });
  }

  async function testManualRecoveryActionReturnsNullForNonFailureStatus() {
    const { getManualRecoveryAction } = await import('./action');
    const action = getManualRecoveryAction({ status: UpdatedStatus.Available, version: '5.3.1' });
    assert.equal(action, null);
  }

  async function testManualRecoveryHelperDoesNotOpenBrowser() {
    const { getManualRecoveryAction } = await import('./action');
    let openedUrl: string | undefined;
    Object.assign(globalThis as Record<string, unknown>, {
      openWebPage: (url: string) => {
        openedUrl = url;
      },
    });
    const action = getManualRecoveryAction({
      status: UpdatedStatus.UpdateFailed,
      version: '5.3.1',
    });
    assert.equal(openedUrl, undefined);
    assert.notEqual(action, null);
  }

  await testRepeatedChecksShareOneJcefRequestAndReachAvailable();
  await testCheckReachesNotAvailableAndFailureStates();
  await testCheckPassesFailureFieldsToDetail();
  await testRepeatedDownloadAndRestartUseOneJcefRequestEach();
  await testDownloadFailurePreservesRecoveryFields();
  await testManualRecoveryActionUsesConstantReleasesPageForCheckFailure();
  await testManualRecoveryActionUsesValidatedReleasePageUrl();
  await testManualRecoveryActionDerivesTagUrlWhenReleasePageUrlMissing();
  await testManualRecoveryActionReturnsNullForNonFailureStatus();
  await testManualRecoveryHelperDoesNotOpenBrowser();
  console.log('Hot update check action tests passed');
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
