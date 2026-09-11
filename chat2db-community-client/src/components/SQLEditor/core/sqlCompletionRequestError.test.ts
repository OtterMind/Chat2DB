import assert from 'node:assert/strict';
import { isExpectedSqlCompletionPermissionError } from './sqlCompletionRequestError';

assert.equal(isExpectedSqlCompletionPermissionError({ errorCode: 'NO_DATA_ACCESS_PERMISSION' }), true);
assert.equal(isExpectedSqlCompletionPermissionError({ errorCode: 'NO_DATA_ACCESS_PERMISSION_DETAIL' }), true);
assert.equal(isExpectedSqlCompletionPermissionError({ errorCode: 'common.permissionDenied' }), true);
assert.equal(isExpectedSqlCompletionPermissionError({ errorCode: 'common.systemError' }), false);
assert.equal(isExpectedSqlCompletionPermissionError(new Error('network error')), false);

console.log('SQL completion request error tests passed');
