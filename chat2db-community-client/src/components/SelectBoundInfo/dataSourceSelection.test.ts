import assert from 'node:assert/strict';
import type { DatabaseTypeCode } from '@/constants/common';
import { createCachedDataSourceSelection } from './dataSourceSelection';

assert.deepEqual(
  createCachedDataSourceSelection({
    dataSourceId: 17,
    dataSourceName: '  deleted-orders  ',
    databaseType: 'MYSQL' as DatabaseTypeCode,
    environmentId: 3,
    environment: { id: 3, name: 'Production', shortName: 'PROD', color: '#FF0000' },
    identityColor: '#112233',
    watermarkEnabled: true,
    watermarkContent: 'Finance',
  }),
  {
    value: '17',
    label: 'deleted-orders',
    title: '  deleted-orders  ',
    dataSourceId: 17,
    environmentId: 3,
    environment: { id: 3, name: 'Production', shortName: 'PROD', color: '#FF0000' },
    identityColor: '#112233',
    watermarkEnabled: true,
    watermarkContent: 'Finance',
    databaseType: 'MYSQL',
  },
);
assert.equal(createCachedDataSourceSelection({ dataSourceId: 18 }).label, '');

console.log('Cached data source selection tests passed');
