import assert from 'node:assert/strict';
import { reportLoadSqlError, resolveLoadSqlErrorMessage } from './loadSqlError';

assert.equal(resolveLoadSqlErrorMessage({ errorMessage: '数据源不存在', message: 'Request failed' }), '数据源不存在');
assert.equal(resolveLoadSqlErrorMessage(new Error('http error')), 'http error');
assert.equal(resolveLoadSqlErrorMessage(''), 'Unknown Error');
assert.equal(resolveLoadSqlErrorMessage({ errorMessage: '' }), 'Unknown Error');
assert.equal(resolveLoadSqlErrorMessage({ errorMessage: 500 }), 'Unknown Error');
assert.equal(resolveLoadSqlErrorMessage(null), 'Unknown Error');
assert.equal(resolveLoadSqlErrorMessage(undefined), 'Unknown Error');

const reported: string[] = [];
const returned = reportLoadSqlError({ errorMessage: 'http error' }, (message) => reported.push(message));
assert.deepEqual(reported, ['http error']);
assert.equal(returned, 'http error');

const withoutBanner: string[] = [];
assert.equal(reportLoadSqlError(new Error('network down'), undefined), 'network down');
assert.deepEqual(withoutBanner, []);

const missingBanner = reportLoadSqlError({}, (message) => withoutBanner.push(message));
assert.equal(missingBanner, 'Unknown Error');
assert.deepEqual(withoutBanner, ['Unknown Error']);

console.log('load SQL error tests passed');
