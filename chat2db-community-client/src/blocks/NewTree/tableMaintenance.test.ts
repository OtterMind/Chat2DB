import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { ImportExportTaskStatus } from '@/constants/importExport';
import { OperationColumn } from '@/constants/tree';
import {
  canShowTableMaintenanceOperation,
  getSupportedTableMaintenanceOperations,
  isTableMaintenanceTaskTerminal,
  refreshAfterTableMaintenanceTaskCompletes,
} from './tableMaintenance';

assert.deepEqual(
  getSupportedTableMaintenanceOperations(DatabaseTypeCode.MYSQL, 'InnoDB'),
  [OperationColumn.AnalyzeTable, OperationColumn.OptimizeTable, OperationColumn.CheckTable],
  'InnoDB must not offer REPAIR TABLE',
);

assert.deepEqual(
  getSupportedTableMaintenanceOperations(DatabaseTypeCode.MYSQL, 'MyISAM'),
  [
    OperationColumn.AnalyzeTable,
    OperationColumn.OptimizeTable,
    OperationColumn.CheckTable,
    OperationColumn.RepairTable,
  ],
  'MyISAM offers the full MySQL table maintenance menu',
);

assert.deepEqual(
  getSupportedTableMaintenanceOperations(DatabaseTypeCode.MYSQL, 'archive'),
  [
    OperationColumn.AnalyzeTable,
    OperationColumn.OptimizeTable,
    OperationColumn.CheckTable,
    OperationColumn.RepairTable,
  ],
  'ARCHIVE engine matching is case-insensitive',
);

assert.deepEqual(
  getSupportedTableMaintenanceOperations(DatabaseTypeCode.MYSQL, 'CSV'),
  [
    OperationColumn.AnalyzeTable,
    OperationColumn.OptimizeTable,
    OperationColumn.CheckTable,
    OperationColumn.RepairTable,
  ],
  'CSV is repairable on MySQL 5.7 and 8.0',
);

assert.equal(
  canShowTableMaintenanceOperation(OperationColumn.RepairTable, DatabaseTypeCode.POSTGRESQL, 'MyISAM'),
  false,
  'non-MySQL table menus must not offer MySQL maintenance operations',
);

assert.equal(
  canShowTableMaintenanceOperation(OperationColumn.RepairTable, DatabaseTypeCode.MYSQL, undefined),
  false,
  'unknown engines must not offer REPAIR TABLE',
);

assert.deepEqual(
  [
    ImportExportTaskStatus.SUCCESS,
    ImportExportTaskStatus.FAILED,
    ImportExportTaskStatus.CANCELLED,
  ].map(isTableMaintenanceTaskTerminal),
  [true, true, true],
  'maintenance refresh should run after every terminal task state',
);

assert.deepEqual(
  [
    ImportExportTaskStatus.PENDING,
    ImportExportTaskStatus.RUNNING,
  ].map(isTableMaintenanceTaskTerminal),
  [false, false],
  'maintenance refresh should wait while the task is active',
);

async function testRefreshPollsUntilTerminalTaskStatus() {
  const statuses = [ImportExportTaskStatus.PENDING, ImportExportTaskStatus.RUNNING, ImportExportTaskStatus.SUCCESS];
  const requestedTaskIds: number[] = [];
  let refreshCount = 0;

  await refreshAfterTableMaintenanceTaskCompletes(
    42,
    async ({ taskId }) => {
      requestedTaskIds.push(taskId);
      return { status: statuses.shift()! };
    },
    () => {
      refreshCount += 1;
    },
    0,
  );

  assert.deepEqual(requestedTaskIds, [42, 42, 42], 'maintenance refresh polls the submitted task');
  assert.equal(refreshCount, 1, 'maintenance refresh runs once after task completion');
}

async function testRefreshPollingIsBoundedWhenTaskDetailsStayUnavailable() {
  let attempts = 0;
  let refreshCount = 0;
  await refreshAfterTableMaintenanceTaskCompletes(
    43,
    async () => {
      attempts += 1;
      throw new Error('temporary task detail failure');
    },
    () => {
      refreshCount += 1;
    },
    0,
    3,
  );
  assert.equal(attempts, 3, 'maintenance polling stops after the configured attempt limit');
  assert.equal(refreshCount, 1, 'metadata refresh still runs once when task status cannot be recovered');
}

void Promise.all([
  testRefreshPollsUntilTerminalTaskStatus(),
  testRefreshPollingIsBoundedWhenTaskDetailsStayUnavailable(),
]).then(() => {
  console.log('Table maintenance menu capability tests passed');
});
