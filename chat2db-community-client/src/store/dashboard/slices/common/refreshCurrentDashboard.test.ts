import assert from 'node:assert/strict';
import { runDashboardRefresh } from './refreshCurrentDashboard';

async function testSuccessfulRefresh() {
  let loadedDashboardId: number | undefined;

  const result = await runDashboardRefresh(42, async (dashboardId) => {
    loadedDashboardId = dashboardId;
  });

  assert.equal(result, true);
  assert.equal(loadedDashboardId, 42);
}

async function testFailedRefreshSettles() {
  let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
  try {
    const result = await Promise.race([
      runDashboardRefresh(42, async () => {
        throw new Error('request failed');
      }),
      new Promise<never>((_resolve, reject) => {
        timeoutHandle = setTimeout(() => reject(new Error('dashboard refresh did not settle')), 100);
      }),
    ]);

    assert.equal(result, false);
  } finally {
    if (timeoutHandle) {
      clearTimeout(timeoutHandle);
    }
  }
}

async function testMissingDashboardSkipsRequest() {
  let requestCount = 0;
  const result = await runDashboardRefresh(undefined, async () => {
    requestCount += 1;
  });

  assert.equal(result, false);
  assert.equal(requestCount, 0);
}

async function testZeroDashboardIdIsLoaded() {
  let loadedDashboardId: number | undefined;
  const result = await runDashboardRefresh(0, async (dashboardId) => {
    loadedDashboardId = dashboardId;
  });

  assert.equal(result, true);
  assert.equal(loadedDashboardId, 0);
}

Promise.all([
  testSuccessfulRefresh(),
  testFailedRefreshSettles(),
  testMissingDashboardSkipsRequest(),
  testZeroDashboardIdIsLoaded(),
])
  .then(() => {
    console.log('Dashboard refresh tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
