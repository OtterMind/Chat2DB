import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { EditColumnOperationType } from '@/constants/editTable';
import {
  hasCheckConstraintRecreation,
  hasEnforcedCheckConstraintChange,
  isMysqlCheckConstraintsSupported,
  isSafeMysqlCheckExpression,
  resolveMysqlCheckConstraintTab,
  validateMysqlCheckConstraints,
} from './mysqlCheckConstraints';

assert.equal(isMysqlCheckConstraintsSupported(DatabaseTypeCode.MYSQL, null), undefined);
assert.equal(isMysqlCheckConstraintsSupported(DatabaseTypeCode.MYSQL, '8.0.16'), true);
assert.equal(isMysqlCheckConstraintsSupported(DatabaseTypeCode.MYSQL, '8.0.34-commercial'), true);
assert.equal(isMysqlCheckConstraintsSupported(DatabaseTypeCode.MYSQL, '5.7.44'), false);
assert.equal(isMysqlCheckConstraintsSupported(DatabaseTypeCode.MYSQL, '8.0.15'), false);
assert.equal(isMysqlCheckConstraintsSupported(DatabaseTypeCode.MYSQL, 'unknown'), false);
assert.equal(isMysqlCheckConstraintsSupported(DatabaseTypeCode.MARIADB, '10.11.0'), false);

let requestedTab = resolveMysqlCheckConstraintTab('check', undefined);
assert.equal(requestedTab, 'check');
requestedTab = resolveMysqlCheckConstraintTab(requestedTab, true);
assert.equal(requestedTab, 'check');
assert.equal(resolveMysqlCheckConstraintTab('check', false), 'column');

assert.equal(isSafeMysqlCheckExpression("`status` IN ('active', 'inactive') AND age >= 0"), true);
assert.equal(isSafeMysqlCheckExpression("email IS NOT NULL OR name = 'anonymous'"), true);
assert.equal(isSafeMysqlCheckExpression('amount >= 0); DROP TABLE users; --'), false);
assert.equal(isSafeMysqlCheckExpression('amount >= 0 /* hidden */'), false);
assert.equal(isSafeMysqlCheckExpression('amount >= (0'), false);

assert.equal(
  validateMysqlCheckConstraints([{ name: '', expression: 'age >= 0', enforced: true } as any]),
  'editTable.check.error.nameRequired',
);
assert.equal(
  validateMysqlCheckConstraints([{ name: 'ck_age', expression: 'age >= 0); DROP TABLE users;', enforced: true } as any]),
  'editTable.check.error.expressionUnsafe',
);

const oldConstraints = [{ name: 'ck_age', expression: 'age >= 0', enforced: false } as any];
const newConstraints = [
  { name: 'ck_age', expression: 'age > 0', enforced: true, editStatus: EditColumnOperationType.Modify } as any,
  { name: 'ck_status', expression: "status in ('active')", enforced: true, editStatus: EditColumnOperationType.Add } as any,
];
assert.equal(hasEnforcedCheckConstraintChange(oldConstraints, newConstraints), true);
assert.equal(hasCheckConstraintRecreation(oldConstraints, newConstraints), true);

console.log('mysqlCheckConstraints tests passed');
