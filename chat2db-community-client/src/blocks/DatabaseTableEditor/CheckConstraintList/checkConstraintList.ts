import { EditColumnOperationType } from '@/constants/editTable';
import type { ICheckConstraintItem } from '@/typings';

export interface ICheckConstraintTableContext {
  databaseName?: string | null;
  schemaName?: string | null;
  tableName?: string | null;
}

export type CheckConstraintField = 'name' | 'expression' | 'enforced';

export function createCheckConstraintDraft(key: string): ICheckConstraintItem {
  return {
    key,
    name: '',
    expression: '',
    enforced: true,
    editStatus: EditColumnOperationType.Add,
  };
}

export function markCheckConstraintUpdated(
  item: ICheckConstraintItem,
  field: CheckConstraintField,
  value: string | boolean,
): ICheckConstraintItem {
  return {
    ...item,
    [field]: value,
    editStatus:
      item.editStatus === EditColumnOperationType.Add ? EditColumnOperationType.Add : EditColumnOperationType.Modify,
  };
}

export function markCheckConstraintDeleted(item: ICheckConstraintItem): ICheckConstraintItem | null {
  if (item.editStatus === EditColumnOperationType.Add) {
    return null;
  }
  return {
    ...item,
    editStatus: EditColumnOperationType.Delete,
  };
}

export function visibleCheckConstraints(constraints: ICheckConstraintItem[]): ICheckConstraintItem[] {
  return constraints.filter((item) => item.editStatus !== EditColumnOperationType.Delete);
}

export function prepareCheckConstraintsForSubmit(
  constraints: ICheckConstraintItem[],
  context: ICheckConstraintTableContext,
): ICheckConstraintItem[] {
  return constraints.map((item) => {
    const data = {
      ...item,
      databaseName: context.databaseName || null,
      schemaName: context.schemaName || null,
      tableName: context.tableName || null,
    };
    delete data.key;
    return data;
  });
}
