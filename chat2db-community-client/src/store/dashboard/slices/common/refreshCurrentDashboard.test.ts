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
  await assert.rejects(
    runDashboardRefresh(42, async () => {
      throw new Error('request failed');
    }),
    /request failed/,
  );
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

async function run() {
  await testSuccessfulRefresh();
  await testFailedRefreshSettles();
  await testMissingDashboardSkipsRequest();
  await testZeroDashboardIdIsLoaded();
}

run()
  .then(() => {
    console.log('Dashboard refresh tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
