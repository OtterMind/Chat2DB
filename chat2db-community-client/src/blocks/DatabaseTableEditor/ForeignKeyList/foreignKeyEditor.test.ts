import assert from 'node:assert/strict';
import { EditColumnOperationType } from '@/constants/editTable';
import {
  flattenForeignKeysForSubmit,
  ForeignKeySubmitValidationError,
  getForeignKeyActionOptions,
  groupForeignKeysForEditor,
  hasForeignKeyRebuild,
} from './foreignKeyEditor';
import type { IForeignKeyInfo, IForeignKeyItem } from '@/typings';

const compositeForeignKeys: IForeignKeyInfo[] = [
  {
    fkName: 'fk_line_order',
    fkColumnName: 'tenant_id',
    fkTableName: 'order_line',
    pkTableName: 'orders',
    pkColumnName: 'tenant_id',
    keySeq: 2,
    updateRule: 0,
    deleteRule: 3,
    editStatus: null,
  },
  {
    fkName: 'fk_line_order',
    fkColumnName: 'order_id',
    fkTableName: 'order_line',
    pkTableName: 'orders',
    pkColumnName: 'id',
    keySeq: 1,
    updateRule: 0,
    deleteRule: 3,
    editStatus: null,
  },
];

assert.deepEqual(groupForeignKeysForEditor(compositeForeignKeys), [
  {
    oldName: 'fk_line_order',
    fkName: 'fk_line_order',
    pkTableName: 'orders',
    updateRule: 0,
    deleteRule: 3,
    editStatus: null,
    columnList: [
      { fkColumnName: 'order_id', pkColumnName: 'id', keySeq: 1 },
      { fkColumnName: 'tenant_id', pkColumnName: 'tenant_id', keySeq: 2 },
    ],
  },
]);

assert.deepEqual(
  groupForeignKeysForEditor([
    {
      fkName: 'fk_mixed_actions',
      fkColumnName: 'parent_id',
      fkTableName: 'child',
      pkTableName: 'parent',
      pkColumnName: 'id',
      keySeq: 1,
      updateRule: 3,
      deleteRule: 2,
      editStatus: null,
    },
  ]),
  [
    {
      oldName: 'fk_mixed_actions',
      fkName: 'fk_mixed_actions',
      pkTableName: 'parent',
      updateRule: 3,
      deleteRule: 2,
      editStatus: null,
      columnList: [{ fkColumnName: 'parent_id', pkColumnName: 'id', keySeq: 1 }],
    },
  ],
);

const edited: IForeignKeyItem[] = [
  {
    oldName: 'fk_line_order',
    fkName: 'fk_line_order_v2',
    pkTableName: 'orders',
    updateRule: 0,
    deleteRule: 2,
    editStatus: EditColumnOperationType.Modify,
    columnList: [
      { fkColumnName: 'order_id', pkColumnName: 'id', keySeq: 1 },
      { fkColumnName: 'tenant_id', pkColumnName: 'tenant_id', keySeq: 2 },
    ],
  },
];

assert.deepEqual(
  flattenForeignKeysForSubmit(edited, { name: 'order_line' }, { databaseName: 'app', schemaName: undefined }),
  [
    {
      oldName: 'fk_line_order',
      fkName: 'fk_line_order_v2',
      fkColumnName: 'order_id',
      fkTableName: 'order_line',
      fkTableCat: 'app',
      fkTableSchem: null,
      pkTableName: 'orders',
      pkColumnName: 'id',
      pkTableCat: 'app',
      pkTableSchem: null,
      keySeq: 1,
      updateRule: 0,
      deleteRule: 2,
      editStatus: EditColumnOperationType.Modify,
    },
    {
      oldName: 'fk_line_order',
      fkName: 'fk_line_order_v2',
      fkColumnName: 'tenant_id',
      fkTableName: 'order_line',
      fkTableCat: 'app',
      fkTableSchem: null,
      pkTableName: 'orders',
      pkColumnName: 'tenant_id',
      pkTableCat: 'app',
      pkTableSchem: null,
      keySeq: 2,
      updateRule: 0,
      deleteRule: 2,
      editStatus: EditColumnOperationType.Modify,
    },
  ],
);

assert.equal(
  flattenForeignKeysForSubmit(
    [
      {
        oldName: 'fk_old',
        fkName: 'fk_old',
        pkTableName: 'parent',
        updateRule: 1,
        deleteRule: 1,
        editStatus: EditColumnOperationType.Delete,
        columnList: [],
      },
    ],
    { name: 'child' },
    { databaseName: 'app', schemaName: undefined },
  )[0].editStatus,
  EditColumnOperationType.Delete,
  'deleted constraints are preserved even without column rows',
);

assert.deepEqual(
  flattenForeignKeysForSubmit(
    [
      {
        fkName: 'fk_nullable_actions',
        pkTableName: 'parent',
        updateRule: null,
        deleteRule: null,
        editStatus: EditColumnOperationType.Add,
        columnList: [{ fkColumnName: 'parent_id', pkColumnName: 'id', keySeq: 1 }],
      },
    ],
    { name: 'child' },
    { databaseName: 'app', schemaName: undefined },
  ).map(({ updateRule, deleteRule }) => ({ updateRule, deleteRule })),
  [{ updateRule: 1, deleteRule: 1 }],
  'null JDBC actions are submitted as explicit RESTRICT defaults',
);

assert.deepEqual(
  flattenForeignKeysForSubmit(
    [
      {
        fkName: 'fk_mixed_actions',
        pkTableName: 'parent',
        updateRule: 3,
        deleteRule: 2,
        editStatus: EditColumnOperationType.Modify,
        columnList: [{ fkColumnName: 'parent_id', pkColumnName: 'id', keySeq: 1 }],
      },
    ],
    { name: 'child' },
    { databaseName: 'app', schemaName: undefined },
  ).map(({ updateRule, deleteRule }) => ({ updateRule, deleteRule })),
  [{ updateRule: 3, deleteRule: 2 }],
  'mixed delete/update actions round trip without sharing one rule',
);

assert.deepEqual(
  flattenForeignKeysForSubmit(
    [
      {
        fkName: '',
        pkTableName: null,
        updateRule: 1,
        deleteRule: 1,
        editStatus: EditColumnOperationType.Add,
        columnList: [],
      },
    ],
    { name: 'child' },
    { databaseName: 'app', schemaName: undefined },
  ),
  [],
  'untouched empty new placeholders do not generate invalid DDL',
);

assert.throws(
  () =>
    flattenForeignKeysForSubmit(
      [
        {
          fkName: 'fk_incomplete',
          pkTableName: null,
          updateRule: 1,
          deleteRule: 1,
          editStatus: EditColumnOperationType.Add,
          columnList: [{ fkColumnName: 'parent_id', pkColumnName: null, keySeq: 1 }],
        },
      ],
      { name: 'child' },
      { databaseName: 'app', schemaName: undefined },
    ),
  ForeignKeySubmitValidationError,
  'partially configured new rows must fail validation instead of being dropped',
);

assert.throws(
  () =>
    flattenForeignKeysForSubmit(
      [
        {
          oldName: 'fk_existing',
          fkName: 'fk_existing',
          pkTableName: 'parent',
          updateRule: 1,
          deleteRule: 1,
          editStatus: EditColumnOperationType.Modify,
          columnList: [{ fkColumnName: 'parent_id', pkColumnName: null, keySeq: 1 }],
        },
      ],
      { name: 'child' },
      { databaseName: 'app', schemaName: undefined },
    ),
  ForeignKeySubmitValidationError,
  'partially configured modified rows must fail validation instead of being dropped',
);

assert.equal(
  getForeignKeyActionOptions((key) =>
    key === 'editTable.foreignKey.actionCascadeHint' ? 'propaga cambios de datos' : key,
  )[1].label,
  'CASCADE (propaga cambios de datos)',
  'CASCADE action warning is supplied by localized option construction',
);

assert.equal(hasForeignKeyRebuild('ALTER TABLE `child`\n\tDROP FOREIGN KEY `fk_old`,\n\tADD CONSTRAINT `fk_new` FOREIGN KEY (`id`) REFERENCES `parent`(`id`);'), true);
assert.equal(hasForeignKeyRebuild('ALTER TABLE `child`\n\tDROP FOREIGN KEY `fk_old`;'), false);

console.log('foreignKeyEditor tests passed');
