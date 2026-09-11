import assert from 'node:assert/strict';
import { connectionCloseRequest } from './connectionCloseRequest';

assert.deepEqual(connectionCloseRequest, {
  path: '/api/connection/close',
  method: 'post',
});

console.log('connection close request contract tests passed');
