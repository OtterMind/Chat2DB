import assert from 'node:assert/strict';
import { resolveDataSourceAuthorization } from './dataSourceAuthorization';

assert.deepEqual(
  resolveDataSourceAuthorization(
    {
      storageType: 'local',
      hasPermission: false,
      isAdmin: false,
    },
    false,
  ),
  {
    hasPermission: false,
    isAdmin: false,
  },
);

assert.deepEqual(resolveDataSourceAuthorization({ storageType: 'LOCAL' }, false), {
  hasPermission: true,
  isAdmin: true,
});

assert.deepEqual(
  resolveDataSourceAuthorization(
    {
      storageType: 'CLOUD',
      hasPermission: false,
      isAdmin: false,
    },
    false,
  ),
  {
    hasPermission: false,
    isAdmin: false,
  },
);

assert.deepEqual(resolveDataSourceAuthorization({}, true), {
  hasPermission: true,
  isAdmin: true,
});

assert.deepEqual(resolveDataSourceAuthorization({}, false), {
  hasPermission: false,
  isAdmin: false,
});

console.log('Data source authorization tests passed');
