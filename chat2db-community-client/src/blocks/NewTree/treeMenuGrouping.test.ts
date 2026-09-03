import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { OperationColumn, TreeNodeType } from '@/constants/tree';
import { dropMenuConfig } from './menuConfig';

function splitGroups(operations: readonly OperationColumn[]): OperationColumn[][] {
  return operations.reduce<OperationColumn[][]>(
    (groups, operation) => {
      if (operation === OperationColumn.Divider) {
        groups.push([]);
      } else {
        groups[groups.length - 1].push(operation);
      }
      return groups;
    },
    [[]],
  );
}

const defaultTableMenu = dropMenuConfig.DEFAULT[TreeNodeType.TABLE];
assert.deepEqual(splitGroups(defaultTableMenu), [
  [OperationColumn.OpenTable, OperationColumn.EditTable],
  [OperationColumn.CreateConsole, OperationColumn.Pin],
  [OperationColumn.CopyName, OperationColumn.ViewDDL, OperationColumn.CopyTable],
  [
    OperationColumn.ImportData,
    OperationColumn.ExportData,
    OperationColumn.ExportSqlFile,
  ],
  [OperationColumn.ChangeAiTableInfoNodataCollection],
  [OperationColumn.TruncateTable, OperationColumn.DeleteTable],
]);

assert.deepEqual(splitGroups(dropMenuConfig.DEFAULT[TreeNodeType.TABLES]), [
  [OperationColumn.CreateConsole],
  [OperationColumn.ViewAllTable, OperationColumn.ViewERModal],
  [OperationColumn.CreateTable],
  [OperationColumn.CopyName, OperationColumn.Refresh],
]);

assert.deepEqual(splitGroups(dropMenuConfig.DEFAULT[TreeNodeType.VIEW]), [
  [OperationColumn.OpenView, OperationColumn.EditView],
  [OperationColumn.CreateConsole],
  [OperationColumn.ChangeAiTableInfoNodataCollection],
  [OperationColumn.CopyName],
]);

assert.deepEqual(splitGroups(dropMenuConfig.DEFAULT[TreeNodeType.DATABASE]), [
  [OperationColumn.CreateConsole, OperationColumn.CreateSchema],
  [OperationColumn.RunSqlFile, OperationColumn.ExportSqlFile, OperationColumn.SchemaSync],
  [OperationColumn.CopyMcpConfig, OperationColumn.CopyName],
  [OperationColumn.DeleteDatabase],
]);

assert.equal(
  dropMenuConfig.DEFAULT[TreeNodeType.DATA_SOURCE].includes(OperationColumn.VariablesStatus),
  true,
  'MySQL datasources use the default menu and must expose Variables & Status',
);
assert.equal(
  dropMenuConfig.REDIS[TreeNodeType.DATA_SOURCE].includes(OperationColumn.VariablesStatus),
  false,
  'Redis datasources must not expose MySQL Variables & Status',
);
assert.equal(
  dropMenuConfig.REDIS[TreeNodeType.DATA_SOURCE].includes(OperationColumn.ActiveTransactions),
  false,
  'Redis datasources must not expose MySQL Active Transactions',
);

assert.deepEqual(splitGroups(dropMenuConfig.DEFAULT[TreeNodeType.SAVE_CONSOLE]), [
  [OperationColumn.OpenConsole, OperationColumn.Rename],
  [OperationColumn.RemoveConsole],
]);

assert.equal(
  dropMenuConfig[DatabaseTypeCode.ORACLE][TreeNodeType.DATA_SOURCE].includes(OperationColumn.ActiveTransactions),
  false,
);
assert.equal(
  dropMenuConfig.DEFAULT[TreeNodeType.DATA_SOURCE].includes(OperationColumn.ActiveTransactions),
  false,
);
assert.deepEqual(dropMenuConfig.DEFAULT[TreeNodeType.ACTIVE_TRANSACTIONS], [OperationColumn.ActiveTransactions]);

console.log('Tree menu grouping tests passed');
