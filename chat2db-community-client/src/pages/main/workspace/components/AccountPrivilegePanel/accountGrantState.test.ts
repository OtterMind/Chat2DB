import assert from 'node:assert/strict';
import type { AccountPrivilege } from '../../../../../service/accountAdmin';
import {
  canRevokeDirectColumnGrant,
  directColumnsFor,
  inheritedSourcesFor,
  parseAccountGrantState,
} from './accountGrantState';

const state = parseAccountGrantState([
  "GRANT SELECT (`id`, `odd``name`), UPDATE (`status`) ON `app`.`orders` TO 'reader'@'%'",
  "GRANT REFERENCES (`customer_id`) ON `app`.`orders` TO 'reader'@'%' WITH GRANT OPTION",
  "GRANT SELECT ON `app`.`orders` TO 'reader'@'%'",
  "GRANT UPDATE ON `app`.* TO 'reader'@'%'",
  "GRANT ALL PRIVILEGES ON *.* TO 'reader'@'%'",
  "GRANT 'reporting'@'%' TO 'reader'@'%'",
]);

assert.deepEqual(directColumnsFor(state, 'app', 'orders', 'SELECT'), ['id', 'odd`name']);
assert.deepEqual(directColumnsFor(state, 'app', 'orders', 'UPDATE'), ['status']);
assert.deepEqual(inheritedSourcesFor(state, 'app', 'orders', 'SELECT'), ['TABLE', 'GLOBAL']);
assert.deepEqual(inheritedSourcesFor(state, 'app', 'orders', 'UPDATE'), ['DATABASE', 'GLOBAL']);
assert.deepEqual(inheritedSourcesFor(state, 'app', 'orders', 'INSERT'), ['GLOBAL']);

assert.equal(
  canRevokeDirectColumnGrant(state, 'app', 'orders', ['SELECT' as AccountPrivilege], ['id']),
  true,
  'a direct subset can be revoked',
);
assert.equal(
  canRevokeDirectColumnGrant(state, 'app', 'orders', ['SELECT' as AccountPrivilege], ['customer_id']),
  false,
  'table-level inherited SELECT is not presented as a revocable column grant',
);
assert.equal(
  canRevokeDirectColumnGrant(state, 'app', 'orders', ['UPDATE' as AccountPrivilege], ['status', 'id']),
  false,
  'every selected column must be directly granted for the selected privilege',
);

console.log('Account grant state tests passed');
