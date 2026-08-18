import assert from 'node:assert/strict';

import { dataSourceDisplayName } from './taskDataSource';

const dataSources = [
  { id: 1784862894119999, alias: 'OneAPI 生产库' },
  { id: 2, alias: '   ' },
];

assert.equal(dataSourceDisplayName(1784862894119999, dataSources, '不可用'), 'OneAPI 生产库');
assert.equal(dataSourceDisplayName(2, dataSources, '不可用'), '不可用');
assert.equal(dataSourceDisplayName(3, dataSources, '不可用'), '不可用');

console.log('Task data source display tests passed.');
