import assert from 'node:assert/strict';
import {
  applyIndexColumnKind,
  getEditableIndexColumns,
  getIndexColumnKind,
  hasExpressionIndexMutation,
  IndexColumnKind,
  isHiddenGeneratedFunctionalIndexColumn,
  isMysqlFunctionalIndexVersion,
  isSupportedMysqlIndexExpression,
  normalizeIndexIncludeColumn,
  supportsMysqlExpressionIndex,
  validateMysqlExpressionIndexRows,
} from './model';
import { EditColumnOperationType } from '@/constants/editTable';

assert.equal(supportsMysqlExpressionIndex('MYSQL'), false);
assert.equal(supportsMysqlExpressionIndex('MYSQL', '8.0.13'), true);
assert.equal(supportsMysqlExpressionIndex('MYSQL', '8.0.34-0ubuntu0.22.04.1'), true);
assert.equal(supportsMysqlExpressionIndex('MYSQL', '8.0.12'), false);
assert.equal(supportsMysqlExpressionIndex('MYSQL', '5.7.44'), false);
assert.equal(supportsMysqlExpressionIndex('MYSQL', null), false);
assert.equal(supportsMysqlExpressionIndex('POSTGRESQL'), false);
assert.equal(isMysqlFunctionalIndexVersion('9.0.0'), true);
assert.equal(isMysqlFunctionalIndexVersion('8.1.0'), true);
assert.equal(isMysqlFunctionalIndexVersion('8.0.12'), false);

assert.equal(getIndexColumnKind({ columnName: 'email', expression: null }), IndexColumnKind.COLUMN);
assert.equal(getIndexColumnKind({ columnName: 'email', expression: 'lower(`email`)' }), IndexColumnKind.COLUMN);
assert.equal(getIndexColumnKind({ columnName: null, expression: 'lower(`email`)' }), IndexColumnKind.EXPRESSION);

assert.deepEqual(
  applyIndexColumnKind(
    {
      columnName: 'email',
      expression: null,
      subPart: 10,
    } as any,
    IndexColumnKind.EXPRESSION,
  ),
  {
    columnName: null,
    expression: null,
    subPart: null,
    indexColumnKind: IndexColumnKind.EXPRESSION,
  },
);

assert.deepEqual(
  normalizeIndexIncludeColumn({
    columnName: null,
    expression: '  lower(`email`)  ',
    subPart: 12,
  } as any),
  {
    columnName: null,
    expression: 'lower(`email`)',
    subPart: null,
  },
);

assert.deepEqual(
  normalizeIndexIncludeColumn({
    columnName: 'email',
    expression: 'lower(`email`)',
    subPart: 12,
  } as any),
  {
    columnName: 'email',
    expression: null,
    subPart: 12,
  },
);

assert.equal(isSupportedMysqlIndexExpression('lower(`email`)'), true);
assert.equal(isSupportedMysqlIndexExpression('(`email` + 1)'), true);
assert.equal(isSupportedMysqlIndexExpression('rand()'), false);
assert.equal(isSupportedMysqlIndexExpression('uuid()'), false);
assert.equal(isSupportedMysqlIndexExpression('`email`'), false);
assert.equal(isSupportedMysqlIndexExpression('email'), false);
assert.equal(isSupportedMysqlIndexExpression('lower(`email`); drop table t'), false);
assert.equal(isSupportedMysqlIndexExpression('lower(`email`) -- comment'), false);
assert.equal(isSupportedMysqlIndexExpression('lower(`email`'), false);

assert.equal(validateMysqlExpressionIndexRows([{ columnName: null, expression: 'lower(`email`)' } as any]), null);
assert.equal(
  validateMysqlExpressionIndexRows([{ columnName: null, expression: 'lower(`email`); drop table t' } as any]),
  'lower(`email`); drop table t',
);

assert.equal(isHiddenGeneratedFunctionalIndexColumn({ name: '!hidden!idx_lower!0!0', generatedColumn: true } as any), true);
assert.equal(isHiddenGeneratedFunctionalIndexColumn({ name: 'visible_generated', generatedColumn: true } as any), false);
assert.deepEqual(
  getEditableIndexColumns([
    { name: 'email', generatedColumn: null } as any,
    { name: '!hidden!idx_lower!0!0', generatedColumn: true } as any,
    { name: null, generatedColumn: null } as any,
  ]).map((column) => column.name),
  ['email'],
);

assert.equal(
  hasExpressionIndexMutation([
    { editStatus: EditColumnOperationType.Add, columnList: [{ expression: 'lower(`email`)' } as any] } as any,
  ]),
  true,
);
assert.equal(
  hasExpressionIndexMutation([
    { editStatus: EditColumnOperationType.Modify, columnList: [{ expression: 'lower(`email`)' } as any] } as any,
  ]),
  true,
);
assert.equal(
  hasExpressionIndexMutation([
    { editStatus: null, columnList: [{ expression: 'lower(`email`)' } as any] } as any,
    { editStatus: EditColumnOperationType.Modify, columnList: [{ columnName: 'email' } as any] } as any,
  ]),
  false,
);

console.log('IncludeCol model.test.ts: all assertions passed');
