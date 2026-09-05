import { DatabaseTypeCode } from '@/constants/common';
import { EditColumnOperationType } from '@/constants/editTable';
import type { ICheckConstraintItem, IEditTableInfo } from '@/typings';

const CHECK_CONSTRAINT_MIN_VERSION = [8, 0, 16] as const;

export function isMysqlCheckConstraintsSupported(
  databaseType?: DatabaseTypeCode | string | null,
  dbVersion?: string | null,
): boolean | undefined {
  if (databaseType !== DatabaseTypeCode.MYSQL) {
    return false;
  }
  if (!dbVersion) {
    return undefined;
  }
  const parts = String(dbVersion || '')
    .match(/\d+/g)
    ?.slice(0, 3)
    .map(Number);
  if (!parts || parts.length < 3 || parts.some((part) => !Number.isFinite(part))) {
    return false;
  }
  for (let index = 0; index < CHECK_CONSTRAINT_MIN_VERSION.length; index++) {
    if (parts[index] !== CHECK_CONSTRAINT_MIN_VERSION[index]) {
      return parts[index] > CHECK_CONSTRAINT_MIN_VERSION[index];
    }
  }
  return true;
}

export function resolveMysqlCheckConstraintTab(currentTab: string, supported: boolean | undefined) {
  if (currentTab === 'check' && supported === false) {
    return 'column';
  }
  return currentTab;
}

export function isSafeMysqlCheckExpression(expression?: string | null) {
  const value = String(expression || '').trim();
  if (!value) {
    return false;
  }

  let parentheses = 0;
  let inSingleQuote = false;
  let inDoubleQuote = false;
  let inBacktick = false;

  for (let index = 0; index < value.length; index++) {
    const current = value[index];
    const next = value[index + 1];

    if (inSingleQuote) {
      if (current === '\\') {
        index++;
        continue;
      }
      if (current === "'" && next === "'") {
        index++;
        continue;
      }
      if (current === "'") {
        inSingleQuote = false;
      }
      continue;
    }
    if (inDoubleQuote) {
      if (current === '\\') {
        index++;
        continue;
      }
      if (current === '"' && next === '"') {
        index++;
        continue;
      }
      if (current === '"') {
        inDoubleQuote = false;
      }
      continue;
    }
    if (inBacktick) {
      if (current === '`' && next === '`') {
        index++;
        continue;
      }
      if (current === '`') {
        inBacktick = false;
      }
      continue;
    }

    if (current === "'") {
      inSingleQuote = true;
      continue;
    }
    if (current === '"') {
      inDoubleQuote = true;
      continue;
    }
    if (current === '`') {
      inBacktick = true;
      continue;
    }
    if (current === ';' || current === '#' || (current === '-' && next === '-') || (current === '/' && next === '*')) {
      return false;
    }
    if (current === '(') {
      parentheses++;
      continue;
    }
    if (current === ')') {
      parentheses--;
      if (parentheses < 0) {
        return false;
      }
    }
  }

  return parentheses === 0 && !inSingleQuote && !inDoubleQuote && !inBacktick;
}

export function validateMysqlCheckConstraint(constraint: ICheckConstraintItem) {
  if (!constraint.name?.trim()) {
    return 'editTable.check.error.nameRequired';
  }
  if (!constraint.expression?.trim()) {
    return 'editTable.check.error.expressionRequired';
  }
  if (!isSafeMysqlCheckExpression(constraint.expression)) {
    return 'editTable.check.error.expressionUnsafe';
  }
  return null;
}

export function validateMysqlCheckConstraints(constraints: ICheckConstraintItem[] = []) {
  for (const constraint of constraints) {
    const errorKey = validateMysqlCheckConstraint(constraint);
    if (errorKey) {
      return errorKey;
    }
  }
  return null;
}

export function hasEnforcedCheckConstraintChange(
  oldConstraints: IEditTableInfo['checkConstraintList'] = [],
  newConstraints: ICheckConstraintItem[] = [],
) {
  const oldByName = new Map(oldConstraints.map((constraint) => [constraint.name, constraint]));
  return newConstraints.some((constraint) => {
    if (constraint.editStatus === EditColumnOperationType.Add) {
      return constraint.enforced !== false;
    }
    if (constraint.editStatus === EditColumnOperationType.Modify && constraint.enforced !== false) {
      return oldByName.get(constraint.name)?.enforced === false;
    }
    return false;
  });
}

export function hasCheckConstraintRecreation(
  oldConstraints: IEditTableInfo['checkConstraintList'] = [],
  newConstraints: ICheckConstraintItem[] = [],
) {
  const oldByName = new Map(oldConstraints.map((constraint) => [constraint.name, constraint]));
  return newConstraints.some(
    (constraint) =>
      constraint.editStatus === EditColumnOperationType.Modify &&
      oldByName.get(constraint.name)?.expression !== constraint.expression,
  );
}
