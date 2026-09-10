import assert from 'node:assert/strict';
import { redactForLog } from './redactForLog';

{
  const input = {
    uuid: 'req-1',
    message: {
      username: 'demo',
      password: 'secret-password',
      nested: {
        accessToken: 'secret-token',
      },
      items: [
        { apiKey: 'secret-key', keep: 'visible' },
        'plain-value',
      ],
    },
  };

  const output = redactForLog(input);

  assert.equal(typeof output, 'object', 'redacted payload stays inspectable in console output');
  assert.notEqual(output, input, 'redaction returns a cloned structure');
  assert.equal(output.message.password, '***');
  assert.equal(output.message.nested.accessToken, '***');
  assert.equal(output.message.items[0].apiKey, '***');
  assert.equal(output.message.items[0].keep, 'visible');
  assert.equal(output.message.items[1], 'plain-value');
  assert.equal(input.message.password, 'secret-password', 'redaction does not mutate the original payload');
}
