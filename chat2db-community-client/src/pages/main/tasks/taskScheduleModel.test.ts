import assert from 'node:assert/strict';
import {
  canOpenScheduledTask,
  cronFromScheduleValues,
  parseTaskScheduleRoute,
  sameDataScope,
  taskScheduleRoutePath,
} from './taskScheduleModel';

assert.equal(cronFromScheduleValues({ scheduleType: 'ONCE' }), undefined);
assert.equal(cronFromScheduleValues({ scheduleType: 'CRON', preset: 'DAILY', time: '09:05' }), '5 9 * * *');
assert.equal(cronFromScheduleValues({ scheduleType: 'CRON', preset: 'WEEKDAYS', time: '18:30' }), '30 18 * * 1-5');
assert.equal(cronFromScheduleValues({ scheduleType: 'CRON', preset: 'WEEKLY', time: '08:00', weekday: 0 }), '0 8 * * 0');
assert.equal(cronFromScheduleValues({ scheduleType: 'CRON', preset: 'CUSTOM', cronExpression: ' 0 9 * * 1-5 ' }), '0 9 * * 1-5');

assert.equal(canOpenScheduledTask('task-1', 'AVAILABLE'), true);
assert.equal(canOpenScheduledTask('task-1', 'ARCHIVED'), false);
assert.equal(canOpenScheduledTask('task-1', 'DELETED'), false);
assert.equal(canOpenScheduledTask(undefined, 'AVAILABLE'), false);

assert.equal(sameDataScope(
  { dataSourceId: 1, databaseName: 'oneapi', schemaName: 'public', tableNames: ['logs'] },
  { dataSourceId: 1, databaseName: 'oneapi', schemaName: 'public', tableNames: ['logs'] },
), true);
assert.equal(sameDataScope(
  { dataSourceId: 1, databaseName: 'oneapi', schemaName: 'public' },
  { dataSourceId: 1, databaseName: 'other', schemaName: 'public' },
), false);

assert.deepEqual(parseTaskScheduleRoute('/tasks'), { open: false, createMode: false });
assert.deepEqual(parseTaskScheduleRoute('#/tasks/schedules'), { open: true, createMode: true });
assert.deepEqual(parseTaskScheduleRoute('/tasks/schedules/new'), { open: true, createMode: true });
assert.deepEqual(parseTaskScheduleRoute('/tasks/schedules/schedule%201'), {
  open: true,
  createMode: false,
  scheduleId: 'schedule 1',
});
assert.equal(taskScheduleRoutePath(), '/tasks/schedules/new');
assert.equal(taskScheduleRoutePath('schedule 1'), '/tasks/schedules/schedule%201');

console.log('Task schedule model tests passed.');
