import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { EditColumnOperationType, NullableType } from '@/constants/editTable';
import type { IColumnItemNew, IEditTableInfo } from '@/typings';
import {
  canEditMysqlGeneratedColumns,
  hasGeneratedColumnStorageConversion,
  isGeneratedColumn,
  normalizeGeneratedColumnForSubmit,
  validateGeneratedColumnExpression,
} from './generatedColumn';

function column(overrides: Partial<IColumnItemNew>): IColumnItemNew {
  return {
    editStatus: null,
    oldName: null,
    name: 'total',
    databaseName: null,
    schemaName: null,
    tableName: null,
    columnType: 'INT',
    dataType: null,
    defaultValue: null,
    autoIncrement: null,
    comment: null,
    primaryKey: null,
    primaryKeyOrder: null,
    typeName: null,
    columnSize: null,
    bufferLength: null,
    decimalDigits: null,
    numPrecRadix: null,
    sqlDataType: null,
    sqlDatetimeSub: null,
    charOctetLength: null,
    ordinalPosition: null,
    nullable: NullableType.Null,
    generatedColumn: null,
    generationExpression: null,
    generatedColumnType: null,
    charSetName: null,
    collationName: null,
    value: null,
    ...overrides,
  };
}

function table(columns: IColumnItemNew[]): IEditTableInfo {
  return {
    name: 'orders',
    comment: null,
    charset: null,
    engine: null,
    incrementValue: null,
    columnList: columns,
    indexList: [],
  };
}

assert.equal(canEditMysqlGeneratedColumns(DatabaseTypeCode.MYSQL, true), true);
assert.equal(canEditMysqlGeneratedColumns(DatabaseTypeCode.MYSQL, false), false);
assert.equal(canEditMysqlGeneratedColumns(DatabaseTypeCode.POSTGRESQL, true), false);

assert.equal(validateGeneratedColumnExpression('`price` * `quantity`'), true);
assert.equal(validateGeneratedColumnExpression("concat(`first`, '-', `last`)"), true);
assert.equal(validateGeneratedColumnExpression('`price`) STORED, `injected` INT'), false);
assert.equal(validateGeneratedColumnExpression('`price`; DROP TABLE `orders`'), false);
assert.equal(validateGeneratedColumnExpression('concat(`drop`, "table")'), true);

const generated = normalizeGeneratedColumnForSubmit(
  column({
    generationExpression: ' `price` * `quantity` ',
    generatedColumnType: null,
    defaultValue: '0',
    autoIncrement: true,
    onUpdateCurrentTimestamp: true,
  }),
  true,
);
assert.equal(generated.generatedColumn, true);
assert.equal(generated.generationExpression, '`price` * `quantity`');
assert.equal(generated.generatedColumnType, 'VIRTUAL');
assert.equal(generated.defaultValue, null);
assert.equal(generated.autoIncrement, false);
assert.equal(generated.onUpdateCurrentTimestamp, false);
assert.equal(isGeneratedColumn(generated), true);

const hiddenByCapability = normalizeGeneratedColumnForSubmit(
  column({ generationExpression: '`price` * 2', generatedColumnType: 'STORED' }),
  false,
);
assert.equal(hiddenByCapability.generatedColumn, false);
assert.equal(hiddenByCapability.generationExpression, null);
assert.equal(hiddenByCapability.generatedColumnType, null);

const hiddenExpression = normalizeGeneratedColumnForSubmit(
  column({ generatedColumn: true, generationExpression: null, generatedColumnType: 'STORED', comment: 'keep generated' }),
  true,
);
assert.equal(hiddenExpression.generatedColumn, true);
assert.equal(hiddenExpression.generationExpression, null);
assert.equal(hiddenExpression.generatedColumnType, 'STORED');
assert.equal(hiddenExpression.defaultValue, null);

assert.equal(
  hasGeneratedColumnStorageConversion(
    table([column({ name: 'total', generatedColumn: true, generationExpression: '`price` * 2', generatedColumnType: 'VIRTUAL' })]),
    table([
      column({
        name: 'total',
        oldName: 'total',
        editStatus: EditColumnOperationType.Modify,
        generatedColumn: true,
        generationExpression: '`price` * 2',
        generatedColumnType: 'STORED',
      }),
    ]),
  ),
  true,
);
assert.equal(
  hasGeneratedColumnStorageConversion(
    table([column({ name: 'total', generatedColumn: true, generationExpression: '`price` * 2', generatedColumnType: 'VIRTUAL' })]),
    table([
      column({
        name: 'total',
        oldName: 'total',
        editStatus: EditColumnOperationType.Modify,
        generatedColumn: true,
        generationExpression: '`price` * 2',
        generatedColumnType: 'VIRTUAL',
      }),
    ]),
  ),
  false,
);

console.log('generatedColumn tests passed');
