import assert from 'node:assert/strict';
import test from 'node:test';
import { redactForLog } from './redactForLog';

test('redacts connection credentials in both request and response log copies', () => {
  const connection = {
    user: 'demo',
    password: 'database-password',
    url: 'jdbc:postgresql://localhost/db?password=url-password',
    ssh: { password: 'ssh-password', passphrase: 'ssh-passphrase', use: false },
    extendInfo: [
      { key: 'password', value: 'property-password', required: true },
      { key: 'connectTimeout', value: '10' },
    ],
  };
  const redactedConnection = {
    user: 'demo',
    password: '***',
    url: '***',
    ssh: { password: '***', passphrase: '***', use: false },
    extendInfo: [
      { key: 'password', value: '***', required: true },
      { key: 'connectTimeout', value: '10' },
    ],
  };
  const request = { uuid: 'req-1', requestUrl: '/api/connection/datasource/create', message: connection };
  const response = { uuid: 'req-1', message: { success: true, data: connection } };
  const before = JSON.stringify({ request, response });

  const requestLog = redactForLog(request);
  const responseLog = redactForLog(response);

  assert.notEqual(requestLog, request);
  assert.notEqual(responseLog, response);
  assert.deepEqual(requestLog, { ...request, message: redactedConnection });
  assert.deepEqual(responseLog, { ...response, message: { success: true, data: redactedConnection } });
  assert.equal(JSON.stringify({ request, response }), before, 'real payloads must remain unchanged');
});

test('handles nested arrays, mixed-case credential keys and named connection properties', () => {
  const input = {
    headers: { Authorization: 'Bearer credential', 'Accept-Language': 'en' },
    items: [
      { api_key: 'key', 'API-KEY': 'key', accessToken: 'token', clientSecret: 'secret' },
      [{ key: 'SSLPassword', value: 'certificate-password' }, { key: 'apiKey', value: 'api-key' }],
      { key: 'applicationName', value: 'review' },
      { key: 42, value: 'ordinary-value' },
    ],
  };

  assert.deepEqual(redactForLog(input), {
    headers: { Authorization: '***', 'Accept-Language': 'en' },
    items: [
      { api_key: '***', 'API-KEY': '***', accessToken: '***', clientSecret: '***' },
      [{ key: 'SSLPassword', value: '***' }, { key: 'apiKey', value: '***' }],
      { key: 'applicationName', value: 'review' },
      { key: 42, value: 'ordinary-value' },
    ],
  });
});

test('omits JDBC strings across dialects instead of parsing credential delimiters', () => {
  const urls = [
    'jdbc:postgresql://localhost/db?password=p%26ss&ssl=true',
    'jdbc:mysql://user:password@localhost/db',
    'jdbc:sqlserver://localhost;user=demo;password={p;as}}s}',
    'jdbc:oracle:thin:demo/password@localhost:1521/service',
    '  JDBC:postgresql://localhost/db?%70assword=secret',
    'jdbc:h2:mem:review',
  ];

  assert.deepEqual(redactForLog({ urls }), { urls: urls.map(() => '***') });
  assert.equal(urls[0], 'jdbc:postgresql://localhost/db?password=p%26ss&ssl=true');
});

test('preserves ordinary values and returns an independent log snapshot', () => {
  const input = { message: { rows: [null, false, 0, '', 'SELECT 1'], comment: 'visible' }, data: undefined };
  const output = redactForLog(input);
  assert.deepEqual(output, input);
  input.message.comment = 'changed after logging';
  assert.deepEqual(output, { message: { rows: [null, false, 0, '', 'SELECT 1'], comment: 'visible' }, data: undefined });
  for (const value of [null, undefined, false, 0, '', 'CHAT2DB_IPC_RESPONSE_SERVICE_STATUS_SUCCESS']) {
    assert.equal(redactForLog(value), value);
  }
});
