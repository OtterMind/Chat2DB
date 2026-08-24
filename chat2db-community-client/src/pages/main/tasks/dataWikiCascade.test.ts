import assert from 'node:assert/strict';

import {
  dataWikiSelectionsFromCascadeOptions,
  dataWikiSelectionsFromCascadeValue,
} from './dataWikiCascade';

const dataSources = [{ id: 7, alias: 'Sales' }];

assert.deepEqual(
  dataWikiSelectionsFromCascadeValue(
    [
      ['datasource:7', 'database:crm', 'schema:public', 'table:customer'],
      ['datasource:7', 'database:warehouse', 'schema:reporting', 'table:daily_sales'],
    ],
    dataSources,
  ),
  [
    {
      dataSourceId: 7,
      dataSourceName: 'Sales',
      databaseName: 'crm',
      schemaName: 'public',
      tableName: 'customer',
    },
    {
      dataSourceId: 7,
      dataSourceName: 'Sales',
      databaseName: 'warehouse',
      schemaName: 'reporting',
      tableName: 'daily_sales',
    },
  ],
);

assert.deepEqual(
  dataWikiSelectionsFromCascadeValue(['datasource:7', 'table:local_table'], dataSources),
  [
    {
      dataSourceId: 7,
      dataSourceName: 'Sales',
      databaseName: undefined,
      schemaName: undefined,
      tableName: 'local_table',
    },
  ],
);

assert.deepEqual(
  dataWikiSelectionsFromCascadeOptions(
    [
      [
        { kind: 'DATA_SOURCE', dataSourceId: 7 },
        { kind: 'DATABASE', dataSourceId: 7, databaseName: 'crm' },
        { kind: 'SCHEMA', dataSourceId: 7, databaseName: 'crm', schemaName: 'public' },
        {
          kind: 'TABLE',
          dataSourceId: 7,
          dataSourceName: 'Sales',
          databaseName: 'crm',
          schemaName: 'public',
          tableName: 'customer',
        },
      ],
    ],
    [['unexpected-runtime-value']],
    dataSources,
  ),
  [
    {
      dataSourceId: 7,
      dataSourceName: 'Sales',
      databaseName: 'crm',
      schemaName: 'public',
      tableName: 'customer',
    },
  ],
);
