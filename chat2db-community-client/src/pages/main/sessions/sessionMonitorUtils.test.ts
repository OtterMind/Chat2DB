import assert from 'node:assert/strict';

import {
  createKillSessionRequest,
  createSessionRequest,
  filterDbSessions,
  formatKillOutcomeResult,
  formatKillSessionResult,
  formatKillSessionSql,
  isKillActionDisabled,
} from './sessionMonitorUtils';

const sessions = [
  {
    id: 12,
    user: 'ops001_admin',
    host: '127.0.0.1:61000',
    db: 'chat2db_ops',
    command: 'Query',
    time: 3,
    state: 'User sleep',
    info: 'SELECT SLEEP(60)',
    current: true,
  },
  {
    id: 13,
    user: 'ops001_owner',
    host: '127.0.0.1:61001',
    db: null,
    command: 'Sleep',
    time: 30,
    state: null,
    info: null,
    current: false,
  },
  {
    id: 14,
    user: 'ops001_reporter',
    host: '127.0.0.1:61002',
    db: 'analytics',
    command: 'Query',
    time: 180,
    state: 'Sending data',
    info: 'SELECT * FROM sales',
    current: false,
  },
];

assert.deepEqual(filterDbSessions(sessions, { keyword: '' }), sessions);
assert.deepEqual(filterDbSessions(sessions, { keyword: 'sleep' }).map((session) => session.id), [12, 13]);
assert.deepEqual(filterDbSessions(sessions, { keyword: 'owner' }).map((session) => session.id), [13]);
assert.deepEqual(filterDbSessions(sessions, { keyword: '61000' }).map((session) => session.id), [12]);
assert.deepEqual(filterDbSessions(sessions, { user: 'reporter' }).map((session) => session.id), [14]);
assert.deepEqual(filterDbSessions(sessions, { database: 'analytics' }).map((session) => session.id), [14]);
assert.deepEqual(filterDbSessions(sessions, { database: '-' }).map((session) => session.id), [13]);
assert.deepEqual(filterDbSessions(sessions, { state: 'sending' }).map((session) => session.id), [14]);
assert.deepEqual(filterDbSessions(sessions, { minDurationSeconds: 30 }).map((session) => session.id), [13, 14]);
assert.deepEqual(
  filterDbSessions(sessions, {
    keyword: 'query',
    user: 'reporter',
    database: 'analytics',
    state: 'sending',
    minDurationSeconds: 120,
  }).map((session) => session.id),
  [14],
);

assert.equal(createSessionRequest(undefined), null);
assert.deepEqual(createSessionRequest(42, ''), { dataSourceId: 42, databaseName: undefined });
assert.deepEqual(createSessionRequest(42, 'chat2db_ops'), { dataSourceId: 42, databaseName: 'chat2db_ops' });

assert.deepEqual(createKillSessionRequest({ dataSourceId: 42 }, sessions[0], 'QUERY'), {
  dataSourceId: 42,
  connectionId: 12,
  killType: 'QUERY',
});
assert.equal(formatKillSessionResult(sessions[0], 'CONNECTION'), 'CONNECTION:12:ops001_admin');
assert.equal(formatKillSessionSql(sessions[0], 'QUERY'), 'KILL QUERY 12');
assert.equal(formatKillSessionSql(sessions[0], 'CONNECTION'), 'KILL CONNECTION 12');
assert.equal(
  formatKillOutcomeResult({
    connectionId: 12,
    killType: 'QUERY',
    status: 'ALREADY_FINISHED',
    sql: 'KILL QUERY 12',
  }),
  'ALREADY_FINISHED:12:KILL QUERY 12',
);
assert.equal(isKillActionDisabled(sessions[0]), true);
assert.equal(isKillActionDisabled(sessions[1]), false);

console.log('Session monitor utility tests passed.');
