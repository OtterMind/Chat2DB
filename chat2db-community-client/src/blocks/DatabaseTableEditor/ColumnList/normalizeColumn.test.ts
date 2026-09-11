import { normalizeColumnForSubmit } from './normalizeColumn';
import type { IColumnItemNew } from '@/typings';

function assertEqual(actual: any, expected: any, message: string) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

function column(overrides: Partial<IColumnItemNew>): IColumnItemNew {
  return {
    editStatus: null,
    oldName: null,
    name: 'amount',
    databaseName: null,
    schemaName: null,
    tableName: null,
    columnType: null,
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
    nullable: null,
    generatedColumn: null,
    charSetName: null,
    collationName: null,
    value: null,
    ...overrides,
  };
}

assertEqual(
  normalizeColumnForSubmit(column({ columnType: 'DECIMAL', decimalDigits: '4' })).columnSize,
  10,
  'default DECIMAL precision when scale is set without precision',
);

assertEqual(
  normalizeColumnForSubmit(column({ columnType: 'DECIMAL UNSIGNED', decimalDigits: '4' })).columnSize,
  10,
  'default DECIMAL UNSIGNED precision when scale is set without precision',
);

assertEqual(
  normalizeColumnForSubmit(column({ columnType: 'DECIMAL', columnSize: 12, decimalDigits: '4' })).columnSize,
  12,
  'keep explicit DECIMAL precision',
);

assertEqual(
  normalizeColumnForSubmit(column({ columnType: 'DECIMAL', decimalDigits: null })).columnSize,
  null,
  'do not default DECIMAL precision when scale is empty',
);

assertEqual(
  normalizeColumnForSubmit(column({ columnType: 'VARCHAR', decimalDigits: '4' })).columnSize,
  null,
  'do not default non-DECIMAL precision',
);

assertEqual(
  normalizeColumnForSubmit(column({ visible: false })).visible,
  false,
  'preserve invisible column state for submission',
);

console.log('normalizeColumn tests passed');
