import assert from 'node:assert/strict';
import { taskStopRequest } from './taskStopRequest';

assert.deepEqual(taskStopRequest, {
  path: '/api/task/stop',
  method: 'post',
});

console.log('task stop request contract tests passed');
