import assert from 'node:assert/strict';
import './action.test.setup';
import { UpdatedStatus } from '@/constants/settings';
import { createCheckUpdateCoordinator } from './checkCoordinator';
import {
  COMMUNITY_GITHUB_RELEASES_URL,
  createHotUpdateAction,
  getCommunityGitHubReleaseTagUrl,
  getManualDownloadAction,
  getManualRecoveryAction,
  isWindowsDesktopUpdatePlatform,
} from './action';

void (async () => {
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
      async () => Promise.reject(new Error('GitHub Release unavailable')),
      (detail) => failedUpdates.push(detail),
    );
    assert.equal(await failed(), false);
    assert.deepEqual(failedUpdates, [
      { status: UpdatedStatus.Checking },
      { status: UpdatedStatus.UpdateFailed, failureStage: 'CHECK', failureReason: 'UNKNOWN' },
    ]);
  }

  async function testCompletedChecksRespectShortCooldown() {
    let requestCount = 0;
    const check = createCheckUpdateCoordinator(
      async () => {
        requestCount += 1;
        return { status: UpdatedStatus.NotAvailable };
      },
      () => undefined,
    );
    assert.equal(await check(), false);
    assert.equal(await check(), false);
    assert.equal(requestCount, 1);
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
    await Promise.all([firstRestart, secondRestart]);
    assert.equal(requestCounts['restart-app'] || 0, 0);
    assert.equal(state.updateDetail.status, UpdatedStatus.Installing);
  }

  async function testWindowsInstallationWaitsForNativeExitConfirmation() {
    const callbacks: Record<string, { onSuccess: (data: string) => void }> = {};
    const requestCounts: Record<string, number> = {};
    (globalThis as any).navigator.os_type = 'Windows';
    (globalThis as any).javaQuery = (
      { request, onSuccess }: { request: string; onSuccess: (data: string) => void },
    ) => {
      const command = JSON.parse(request).requestUrl as string;
      requestCounts[command] = (requestCounts[command] || 0) + 1;
      callbacks[command] = { onSuccess };
    };
    const state: { updateDetail: any; setUpdateDetail: (detail: any) => void } = {
      updateDetail: { status: UpdatedStatus.Updated },
      setUpdateDetail: (detail) => Object.assign(state.updateDetail, detail),
    };
    const actions = createHotUpdateAction(() => undefined, () => state as any, {} as any);

    const installation = actions.updateAndRestartApp();
    callbacks['trigger-installation'].onSuccess('true');
    await installation;

    assert.equal(requestCounts['restart-app'] || 0, 0);
    assert.equal(state.updateDetail.status, UpdatedStatus.Installing);
    (globalThis as any).navigator.os_type = 'Windows';
  }

  async function testWindowsCancelFailureAndRetryTransitions() {
    const callbacks: Record<string, { onSuccess: (data: string) => void }> = {};
    const requestCounts: Record<string, number> = {};
    (globalThis as any).navigator.os_type = 'Windows';
    (globalThis as any).javaQuery = (
      { request, onSuccess }: { request: string; onSuccess: (data: string) => void },
    ) => {
      const command = JSON.parse(request).requestUrl as string;
      requestCounts[command] = (requestCounts[command] || 0) + 1;
      callbacks[command] = { onSuccess };
    };
    const state: { updateDetail: any; setUpdateDetail: (detail: any) => void } = {
      updateDetail: { status: UpdatedStatus.Installing, version: '5.3.1' },
      setUpdateDetail: (detail) => Object.assign(state.updateDetail, detail),
    };
    const actions = createHotUpdateAction(() => undefined, () => state as any, {} as any);

    actions.handleApplicationExitResult({ reason: 'INSTALL_UPDATE', result: 'CANCELLED' });
    assert.equal(state.updateDetail.status, UpdatedStatus.Updated);

    actions.handleApplicationExitResult({ reason: 'INSTALL_UPDATE', result: 'FAILED' });
    assert.equal(state.updateDetail.status, UpdatedStatus.UpdateFailed);
    assert.equal(state.updateDetail.failureStage, 'INSTALL');

    const retry = actions.updateAndRestartApp();
    assert.equal(state.updateDetail.status, UpdatedStatus.Installing);
    assert.equal(requestCounts['trigger-installation'], 1);
    callbacks['trigger-installation'].onSuccess('true');
    await retry;
    assert.equal(state.updateDetail.status, UpdatedStatus.Installing);
    (globalThis as any).navigator.os_type = 'Windows';
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
    const action = getManualRecoveryAction({
      status: UpdatedStatus.UpdateFailed,
      failureStage: 'CHECK',
      failureReason: 'NETWORK',
    });
    assert.deepEqual(action, { url: COMMUNITY_GITHUB_RELEASES_URL });
  }

  async function testManualRecoveryActionUsesValidatedReleasePageUrl() {
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
    const action = getManualRecoveryAction({ status: UpdatedStatus.Available, version: '5.3.1' });
    assert.equal(action, null);
  }

  async function testManualRecoveryHelperDoesNotOpenBrowser() {
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

  async function testManualDownloadActionUsesValidatedUrlAndVersionFallback() {
    const expectedUrl = getCommunityGitHubReleaseTagUrl('5.3.1');

    assert.deepEqual(getManualDownloadAction({
      status: UpdatedStatus.Available,
      version: '5.3.1',
      releasePageUrl: expectedUrl,
    }), { url: expectedUrl, version: '5.3.1' });
    assert.deepEqual(getManualDownloadAction({
      status: UpdatedStatus.Available,
      version: '5.3.1',
    }), { url: expectedUrl, version: '5.3.1' });
    assert.deepEqual(getManualDownloadAction({
      status: UpdatedStatus.Available,
      version: '5.3.1',
      releasePageUrl: 'https://example.com/untrusted',
    }), { url: expectedUrl, version: '5.3.1' });
    assert.equal(getManualDownloadAction({ status: UpdatedStatus.Available }), null);
    assert.equal(getManualDownloadAction({ status: UpdatedStatus.Available, version: '../latest' }), null);
  }

  async function testAutomaticDownloadAndInstallationAreWindowsOnly() {
    let bridgeCalls = 0;
    (globalThis as any).javaQuery = () => {
      bridgeCalls += 1;
    };

    for (const platform of ['Mac', 'Linux']) {
      (globalThis as any).navigator.os_type = platform;
      const state: { updateDetail: any; setUpdateDetail: (detail: any) => void } = {
        updateDetail: { status: UpdatedStatus.Available, version: '5.3.1' },
        setUpdateDetail: (detail) => Object.assign(state.updateDetail, detail),
      };
      const actions = createHotUpdateAction(() => undefined, () => state as any, {} as any);
      assert.equal(isWindowsDesktopUpdatePlatform(), false);
      assert.equal(await actions.downloadUpdate(), false);
      state.updateDetail = { status: UpdatedStatus.Updated, version: '5.3.1' };
      await actions.updateAndRestartApp();
      assert.equal(state.updateDetail.status, UpdatedStatus.Updated);
    }

    (globalThis as any).navigator.os_type = 'Windows';
    assert.equal(isWindowsDesktopUpdatePlatform(), true);
    assert.equal(bridgeCalls, 0);
  }

  await testRepeatedChecksShareOneJcefRequestAndReachAvailable();
  await testCheckReachesNotAvailableAndFailureStates();
  await testCompletedChecksRespectShortCooldown();
  await testCheckPassesFailureFieldsToDetail();
  await testRepeatedDownloadAndRestartUseOneJcefRequestEach();
  await testWindowsInstallationWaitsForNativeExitConfirmation();
  await testWindowsCancelFailureAndRetryTransitions();
  await testDownloadFailurePreservesRecoveryFields();
  await testManualRecoveryActionUsesConstantReleasesPageForCheckFailure();
  await testManualRecoveryActionUsesValidatedReleasePageUrl();
  await testManualRecoveryActionDerivesTagUrlWhenReleasePageUrlMissing();
  await testManualRecoveryActionReturnsNullForNonFailureStatus();
  await testManualRecoveryHelperDoesNotOpenBrowser();
  await testManualDownloadActionUsesValidatedUrlAndVersionFallback();
  await testAutomaticDownloadAndInstallationAreWindowsOnly();
  console.log('Hot update check action tests passed');
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
