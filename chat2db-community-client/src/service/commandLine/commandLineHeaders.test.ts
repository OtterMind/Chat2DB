import assert from 'node:assert/strict';
import { buildCommandLineParams } from './commandLineHeaders';

const request = buildCommandLineParams(
  {
    requestUrl: '/api/order/list',
    method: 'get',
    message: undefined,
    headers: {
      'Accept-Language': 'overridden-language',
      'Time-Zone': 'overridden-time-zone',
      'Chat2db-Organization-Id': '42',
      'Chat2db-Organization-Token': 'personal-token',
    },
  },
  'request-id',
  'zh-CN',
  'Asia/Shanghai',
);

assert.deepEqual(request.headers, {
  'Accept-Language': 'zh-CN',
  'Time-Zone': 'Asia/Shanghai',
  'Chat2db-Organization-Id': '42',
  'Chat2db-Organization-Token': 'personal-token',
});
assert.equal(request.uuid, 'request-id');
assert.equal(request.requestUrl, '/api/order/list');

console.log('command line request header tests passed');
