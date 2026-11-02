import assert from 'node:assert/strict';
import { filterSchemaByChartIds } from '@/utils/dashboard';
import {
  captureDashboardChartDeleteMutation,
  isDashboardMutationCurrent,
  resolveDashboardChartDeleteTarget,
  resolveDashboardMutationState,
} from './dashboardMutation';

const dashboardA = {
  id: 1,
  name: 'Dashboard A',
  description: 'original A',
  chartIds: [10, 20],
  schema: JSON.stringify([
    { i: '10', x: 0, y: 0, w: 6, h: 4 },
    { i: '20', x: 6, y: 0, w: 6, h: 4 },
  ]),
};
const dashboardB = { id: 2, name: 'Dashboard B', description: 'active B', chartIds: [30], schema: '[]' };

const delayedUpdateState = resolveDashboardMutationState(
  dashboardB,
  [dashboardA, dashboardB],
  { ...dashboardA, name: 'Dashboard A saved' },
);
assert.deepEqual(
  delayedUpdateState.currentDashboard,
  dashboardB,
  'a delayed update for A must not replace the newly selected dashboard B',
);
assert.equal(delayedUpdateState.dashboardList[0].name, 'Dashboard A saved');
assert.equal(isDashboardMutationCurrent(dashboardB, dashboardA), false);
assert.equal(isDashboardMutationCurrent(dashboardA, dashboardA), true);

const currentUpdateState = resolveDashboardMutationState(
  { ...dashboardA, description: 'newer local description' },
  [dashboardA, dashboardB],
  { id: 1, name: 'Dashboard A saved' },
);
assert.deepEqual(currentUpdateState.currentDashboard, {
  ...dashboardA,
  name: 'Dashboard A saved',
  description: 'newer local description',
});

const deleteMutation = captureDashboardChartDeleteMutation(dashboardA, 10);
const deleteTargetAfterSwitch = resolveDashboardChartDeleteTarget(
  deleteMutation,
  dashboardB,
  filterSchemaByChartIds,
);
assert.deepEqual(deleteTargetAfterSwitch?.chartIds, [20]);
assert.deepEqual(JSON.parse(deleteTargetAfterSwitch?.schema || '[]'), [
  { i: '20', x: 6, y: 0, w: 6, h: 4 },
]);

const deleteStateAfterSwitch = resolveDashboardMutationState(
  dashboardB,
  [dashboardA, dashboardB],
  deleteTargetAfterSwitch!,
);
assert.deepEqual(
  deleteStateAfterSwitch.currentDashboard,
  dashboardB,
  'a delayed chart deletion for A must not remove charts from dashboard B',
);
assert.deepEqual(deleteStateAfterSwitch.dashboardList[0].chartIds, [20]);

const currentAWithNewerName = { ...dashboardA, name: 'Dashboard A renamed while deleting' };
const deleteTargetWithoutSwitch = resolveDashboardChartDeleteTarget(
  deleteMutation,
  currentAWithNewerName,
  filterSchemaByChartIds,
);
assert.equal(deleteTargetWithoutSwitch?.name, 'Dashboard A renamed while deleting');
assert.deepEqual(deleteTargetWithoutSwitch?.chartIds, [20]);

assert.equal(captureDashboardChartDeleteMutation(null, 10), null);
assert.equal(resolveDashboardChartDeleteTarget(null, dashboardB, filterSchemaByChartIds), null);

const listWithoutMutationId = [dashboardA, dashboardB];
assert.equal(
  resolveDashboardMutationState(dashboardB, listWithoutMutationId, { name: 'unsaved' }).dashboardList,
  listWithoutMutationId,
);

console.log('Dashboard mutation ownership tests passed');
