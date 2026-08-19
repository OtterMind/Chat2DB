import assert from 'node:assert/strict';
import {
  activateCascadeRequestGuard,
  beginCascadeRequest,
  createCascadeRequestGuard,
  disposeCascadeRequestGuard,
  getCascadeRequestContextKey,
  isCascadeRequestCurrent,
} from './cascadeRequestGuard';

const guard = createCascadeRequestGuard();
const dataSourceA = { dataSourceId: 1 };
const dataSourceB = { dataSourceId: 2 };

const firstDatabaseRequest = beginCascadeRequest(guard, 'database', dataSourceA);
assert.equal(isCascadeRequestCurrent(guard, firstDatabaseRequest, dataSourceA), true);
assert.equal(isCascadeRequestCurrent(guard, firstDatabaseRequest, dataSourceB), false);

const latestDatabaseRequest = beginCascadeRequest(guard, 'database', dataSourceA);
assert.equal(isCascadeRequestCurrent(guard, firstDatabaseRequest, dataSourceA), false);
assert.equal(isCascadeRequestCurrent(guard, latestDatabaseRequest, dataSourceA), true);

const schemaForOrders = beginCascadeRequest(guard, 'schema', {
  dataSourceId: 1,
  databaseName: 'orders',
});
assert.equal(
  isCascadeRequestCurrent(guard, schemaForOrders, {
    dataSourceId: 1,
    databaseName: 'analytics',
  }),
  false,
);
assert.notEqual(
  getCascadeRequestContextKey('database', { dataSourceId: 1 }),
  getCascadeRequestContextKey('database', { dataSourceId: 2 }),
);

disposeCascadeRequestGuard(guard);
assert.equal(isCascadeRequestCurrent(guard, latestDatabaseRequest, dataSourceA), false);
activateCascadeRequestGuard(guard);
const remountedRequest = beginCascadeRequest(guard, 'database', dataSourceB);
assert.equal(isCascadeRequestCurrent(guard, remountedRequest, dataSourceB), true);

console.log('SelectBoundInfo cascade request guard tests passed');
