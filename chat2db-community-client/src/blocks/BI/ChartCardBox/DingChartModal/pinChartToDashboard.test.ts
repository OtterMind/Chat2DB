import assert from 'node:assert/strict';
import { PinChartToDashboardDependencies, pinChartToDashboard } from './pinChartToDashboard';

const chartDetail = { id: 3, name: 'Revenue' } as any;
const dashboard = { id: 42, name: 'Sales', chartIds: [1, 2], schema: '[]' } as any;

interface Recording {
  events: string[];
  loadingStates: boolean[];
  updatedDashboards: any[];
  createChartError?: Error;
  refreshResult?: boolean;
  refreshError?: Error;
}

function createRecordingDependencies(recording: Recording): PinChartToDashboardDependencies {
  return {
    createChart: async () => {
      recording.events.push('create-chart');
      if (recording.createChartError) {
        throw recording.createChartError;
      }
      return 7;
    },
    getDashboardById: async () => {
      recording.events.push('load-dashboard');
      return dashboard;
    },
    updateDashboard: async (updatedDashboard) => {
      recording.events.push('update-dashboard');
      recording.updatedDashboards.push(updatedDashboard);
      return true;
    },
    refreshCurrentDashboard: async () => {
      recording.events.push('refresh');
      if (recording.refreshError) {
        throw recording.refreshError;
      }
      return recording.refreshResult ?? true;
    },
    closePinChartModal: () => {
      recording.events.push('close-modal');
    },
    showPinSuccessMessage: () => {
      recording.events.push('success-message');
    },
    setSubmitLoading: (loading) => {
      recording.events.push(`submit-loading:${loading}`);
      recording.loadingStates.push(loading);
    },
  };
}

async function testSuccessfulPinShowsSuccessMessage() {
  const recording: Recording = { events: [], loadingStates: [], updatedDashboards: [], refreshResult: true };

  const result = await pinChartToDashboard(chartDetail, dashboard, createRecordingDependencies(recording));

  assert.equal(result, true);
  assert.deepEqual(recording.events, [
    'submit-loading:true',
    'create-chart',
    'load-dashboard',
    'update-dashboard',
    'close-modal',
    'refresh',
    'success-message',
    'submit-loading:false',
  ]);
  assert.equal(recording.updatedDashboards.length, 1);
  assert.deepEqual(recording.updatedDashboards[0].chartIds, [1, 2, 7]);
  assert.equal(recording.loadingStates[recording.loadingStates.length - 1], false);
}

async function testFailedRefreshSkipsSuccessMessageButStillSettles() {
  const recording: Recording = { events: [], loadingStates: [], updatedDashboards: [], refreshResult: false };
  let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
  try {
    const result = await Promise.race([
      pinChartToDashboard(chartDetail, dashboard, createRecordingDependencies(recording)),
      new Promise<never>((_resolve, reject) => {
        timeoutHandle = setTimeout(() => reject(new Error('pin submit did not settle')), 100);
      }),
    ]);

    assert.equal(result, false);
    // The chart was pinned and the modal closed, but the refresh failed:
    // the success message must not be shown.
    assert.equal(recording.events.includes('success-message'), false);
    assert.deepEqual(recording.events, [
      'submit-loading:true',
      'create-chart',
      'load-dashboard',
      'update-dashboard',
      'close-modal',
      'refresh',
      'submit-loading:false',
    ]);
    assert.equal(recording.loadingStates[recording.loadingStates.length - 1], false);
  } finally {
    if (timeoutHandle) {
      clearTimeout(timeoutHandle);
    }
  }
}

async function testRefreshRejectionStillClearsSubmitLoading() {
  const recording: Recording = { events: [], loadingStates: [], updatedDashboards: [], refreshError: new Error('refresh crashed') };

  const result = await pinChartToDashboard(chartDetail, dashboard, createRecordingDependencies(recording));

  assert.equal(result, false);
  assert.equal(recording.events.includes('success-message'), false);
  assert.equal(recording.loadingStates[recording.loadingStates.length - 1], false);
}

async function testFailedPinRequestSettlesWithoutSuccessMessage() {
  const recording: Recording = {
    events: [],
    loadingStates: [],
    updatedDashboards: [],
    createChartError: new Error('request failed'),
  };

  const result = await pinChartToDashboard(chartDetail, dashboard, createRecordingDependencies(recording));

  assert.equal(result, false);
  assert.equal(recording.events.includes('success-message'), false);
  assert.equal(recording.events.includes('close-modal'), false);
  assert.deepEqual(recording.events, ['submit-loading:true', 'create-chart', 'submit-loading:false']);
}

async function testMissingSelectionSkipsSubmit() {
  const recording: Recording = { events: [], loadingStates: [], updatedDashboards: [] };

  const result = await pinChartToDashboard(chartDetail, null, createRecordingDependencies(recording));

  assert.equal(result, false);
  assert.deepEqual(recording.events, []);
  assert.deepEqual(recording.loadingStates, []);
}

Promise.all([
  testSuccessfulPinShowsSuccessMessage(),
  testFailedRefreshSkipsSuccessMessageButStillSettles(),
  testRefreshRejectionStillClearsSubmitLoading(),
  testFailedPinRequestSettlesWithoutSuccessMessage(),
  testMissingSelectionSkipsSubmit(),
])
  .then(() => {
    console.log('Pin chart to dashboard caller tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
