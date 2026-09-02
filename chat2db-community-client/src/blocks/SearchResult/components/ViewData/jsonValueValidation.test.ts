import assert from 'node:assert/strict';
import { isJsonResultCell, isValidJsonValue } from './jsonValueValidation';

for (const value of ['{}', '[]', '"text"', '123', 'true', 'false', 'null']) {
  assert.equal(isValidJsonValue(value), true, `${value} is a valid MySQL JSON value`);
}
for (const value of ['', '{', 'undefined', "'text'", 'NaN']) {
  assert.equal(isValidJsonValue(value), false, `${value} must not reach pending DML`);
}

assert.equal(isJsonResultCell({ value: '{}', valueType: 'JSON' }), true);
assert.equal(isJsonResultCell({ value: '{}', columnType: 'json' }), true);
assert.equal(isJsonResultCell({ value: 'text', columnType: 'VARCHAR' }), false);

console.log('JSON value validation tests passed');
