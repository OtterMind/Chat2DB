import assert from 'node:assert/strict';
import {
  buildPartitionDdlExecuteRequest,
  canInspectMysqlPartitions,
  defaultPartitionDefinition,
  executePartitionPreviewSql,
  getPartitionOperationAvailability,
  isPartitionDropConfirmationValid,
  PARTITION_READBACK_FIELD_KEYS,
} from './partitionOperations';
import { DatabaseTypeCode } from '@/constants/common';

const context = {
  dataSourceId: 42,
  databaseName: 'orders_db',
  tableName: 'orders',
};
const sql = 'ALTER TABLE `orders_db`.`orders` TRUNCATE PARTITION `p202401`';

assert.equal(canInspectMysqlPartitions(DatabaseTypeCode.MYSQL), true);
assert.equal(canInspectMysqlPartitions(DatabaseTypeCode.POSTGRESQL), false);
assert.equal(canInspectMysqlPartitions(null), false);

assert.deepEqual(
  buildPartitionDdlExecuteRequest(context, sql),
  {
    dataSourceId: 42,
    databaseName: 'orders_db',
    tableName: 'orders',
    sql,
  },
  'partition DDL execution must stay bound to the selected datasource/database/table',
);

assert.deepEqual(
  getPartitionOperationAvailability('RANGE COLUMNS'),
  {
    add: true,
    drop: true,
    truncate: true,
    reorganize: true,
    coalesce: false,
    maintain: true,
  },
  'RANGE/LIST partitions expose ADD, DROP, TRUNCATE, REORGANIZE, and maintenance only',
);

for (const method of ['RANGE', 'RANGE COLUMNS', 'LIST', 'LIST COLUMNS']) {
  assert.deepEqual(
    getPartitionOperationAvailability(method),
    {
      add: true,
      drop: true,
      truncate: true,
      reorganize: true,
      coalesce: false,
      maintain: true,
    },
    `${method} partitions expose only RANGE/LIST operations`,
  );
}

assert.deepEqual(
  getPartitionOperationAvailability('LINEAR HASH'),
  {
    add: true,
    drop: false,
    truncate: false,
    reorganize: false,
    coalesce: true,
    maintain: true,
  },
  'HASH/KEY partitions expose ADD, COALESCE, and maintenance only',
);

for (const method of ['HASH', 'LINEAR HASH', 'KEY', 'LINEAR KEY']) {
  assert.deepEqual(
    getPartitionOperationAvailability(method),
    {
      add: true,
      drop: false,
      truncate: false,
      reorganize: false,
      coalesce: true,
      maintain: true,
    },
    `${method} partitions expose only HASH/KEY operations`,
  );
}

assert.deepEqual(
  getPartitionOperationAvailability(null),
  {
    add: false,
    drop: false,
    truncate: false,
    reorganize: false,
    coalesce: false,
    maintain: false,
  },
  'non-partitioned tables expose no partition maintenance actions',
);

assert.equal(defaultPartitionDefinition('LIST COLUMNS'), 'VALUES IN (...)');
assert.equal(defaultPartitionDefinition('RANGE'), 'VALUES LESS THAN (...)');
assert.equal(isPartitionDropConfirmationValid('p202401', ' p202401 '), true);
assert.equal(isPartitionDropConfirmationValid('p202401', 'p202402'), false);
assert.deepEqual(
  PARTITION_READBACK_FIELD_KEYS,
  [
    'partitionName',
    'subpartitionName',
    'ordinalPosition',
    'subpartitionOrdinalPosition',
    'method',
    'subpartitionMethod',
    'expression',
    'subpartitionExpression',
    'description',
    'tableRows',
    'avgRowLength',
    'dataLength',
    'maxDataLength',
    'indexLength',
    'dataFree',
    'createTime',
    'updateTime',
    'checkTime',
    'checksum',
    'comment',
    'nodegroup',
    'tablespaceName',
  ],
  'partition readback keeps the complete information_schema metadata contract',
);

async function main() {
  let executedPayload: unknown;
  let refreshCount = 0;
  await executePartitionPreviewSql({
    context,
    sql,
    executeDDL: async (payload) => {
      executedPayload = payload;
      return { success: true, message: '', originalSql: sql };
    },
    refresh: () => {
      refreshCount++;
    },
  });

  assert.deepEqual(executedPayload, {
    dataSourceId: 42,
    databaseName: 'orders_db',
    tableName: 'orders',
    sql,
  });
  assert.equal(refreshCount, 1, 'successful partition DDL execution refreshes readback');

  console.log('Partition operation tests passed');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
