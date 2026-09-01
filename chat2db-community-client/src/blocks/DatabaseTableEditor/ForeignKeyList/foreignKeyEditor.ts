import { EditColumnOperationType } from '@/constants/editTable';
import type { IDatabaseBaseInfo, IEditTableInfo, IForeignKeyInfo, IForeignKeyItem } from '@/typings';

export const FOREIGN_KEY_VALIDATION_MESSAGE_KEY = 'editTable.foreignKey.validationIncomplete' as const;
const FOREIGN_KEY_ACTION_CASCADE_HINT_KEY = 'editTable.foreignKey.actionCascadeHint' as const;

export class ForeignKeySubmitValidationError extends Error {
  messageKey = FOREIGN_KEY_VALIDATION_MESSAGE_KEY;

  constructor() {
    super(FOREIGN_KEY_VALIDATION_MESSAGE_KEY);
  }
}

export const getForeignKeyActionOptions = (translate: (key: typeof FOREIGN_KEY_ACTION_CASCADE_HINT_KEY) => string) => [
  { label: 'RESTRICT', value: 1 },
  { label: `CASCADE (${translate(FOREIGN_KEY_ACTION_CASCADE_HINT_KEY)})`, value: 0 },
  { label: 'SET NULL', value: 2 },
  { label: 'NO ACTION', value: 3 },
];

const groupKey = (foreignKey: Pick<IForeignKeyInfo, 'fkName' | 'pkTableName'>): string => {
  return `${foreignKey.fkName || ''}\u0000${foreignKey.pkTableName || ''}`;
};

export function groupForeignKeysForEditor(foreignKeyList: IForeignKeyInfo[] | undefined): IForeignKeyItem[] {
  const groups = new Map<string, IForeignKeyInfo[]>();
  foreignKeyList?.forEach((foreignKey) => {
    const key = groupKey(foreignKey);
    groups.set(key, [...(groups.get(key) || []), foreignKey]);
  });

  return Array.from(groups.values()).map((foreignKeyColumns) => {
    const sortedColumns = [...foreignKeyColumns].sort((left, right) => Number(left.keySeq) - Number(right.keySeq));
    const first = sortedColumns[0];
    return {
      oldName: first.oldName || first.fkName,
      fkName: first.fkName,
      pkTableName: first.pkTableName,
      updateRule: first.updateRule ?? null,
      deleteRule: first.deleteRule ?? null,
      editStatus: first.editStatus || null,
      columnList: sortedColumns.map((foreignKeyColumn, index) => ({
        fkColumnName: foreignKeyColumn.fkColumnName,
        pkColumnName: foreignKeyColumn.pkColumnName,
        keySeq: Number(foreignKeyColumn.keySeq || index + 1),
      })),
    };
  });
}

const isCompleteForeignKey = (foreignKey: IForeignKeyItem): boolean => {
  return Boolean(
    foreignKey.fkName &&
      foreignKey.pkTableName &&
      foreignKey.columnList.length &&
      foreignKey.columnList.every((column) => column.fkColumnName && column.pkColumnName),
  );
};

const hasValue = (value?: string | null): boolean => Boolean(value?.trim());

const isEmptyNewForeignKeyPlaceholder = (foreignKey: IForeignKeyItem): boolean => {
  return (
    foreignKey.editStatus === EditColumnOperationType.Add &&
    !hasValue(foreignKey.fkName) &&
    !hasValue(foreignKey.pkTableName) &&
    foreignKey.columnList.every((column) => !hasValue(column.fkColumnName) && !hasValue(column.pkColumnName))
  );
};

export function flattenForeignKeysForSubmit(
  foreignKeyItems: IForeignKeyItem[],
  tableDetails: Pick<IEditTableInfo, 'name'>,
  databaseBaseInfo: Pick<IDatabaseBaseInfo, 'databaseName' | 'schemaName'>,
): IForeignKeyInfo[] {
  return foreignKeyItems.flatMap((foreignKey) => {
    if (foreignKey.editStatus === EditColumnOperationType.Delete) {
      return [
        {
          oldName: foreignKey.oldName || foreignKey.fkName,
          fkName: foreignKey.fkName,
          fkColumnName: null,
          fkTableName: tableDetails?.name || null,
          fkTableCat: databaseBaseInfo.databaseName || null,
          fkTableSchem: databaseBaseInfo.schemaName || null,
          pkTableName: foreignKey.pkTableName,
          pkColumnName: null,
          pkTableCat: databaseBaseInfo.databaseName || null,
          pkTableSchem: databaseBaseInfo.schemaName || null,
          keySeq: 1,
          updateRule: foreignKey.updateRule ?? 1,
          deleteRule: foreignKey.deleteRule ?? 1,
          editStatus: EditColumnOperationType.Delete,
        },
      ];
    }

    if (!isCompleteForeignKey(foreignKey)) {
      if (isEmptyNewForeignKeyPlaceholder(foreignKey)) {
        return [];
      }
      throw new ForeignKeySubmitValidationError();
    }

    return foreignKey.columnList.map((column, index) => ({
      oldName: foreignKey.oldName || foreignKey.fkName,
      fkName: foreignKey.fkName,
      fkColumnName: column.fkColumnName,
      fkTableName: tableDetails?.name || null,
      fkTableCat: databaseBaseInfo.databaseName || null,
      fkTableSchem: databaseBaseInfo.schemaName || null,
      pkTableName: foreignKey.pkTableName,
      pkColumnName: column.pkColumnName,
      pkTableCat: databaseBaseInfo.databaseName || null,
      pkTableSchem: databaseBaseInfo.schemaName || null,
      keySeq: index + 1,
      updateRule: foreignKey.updateRule ?? 1,
      deleteRule: foreignKey.deleteRule ?? 1,
      editStatus: foreignKey.editStatus,
    }));
  });
}

export function hasForeignKeyRebuild(sql: string): boolean {
  return /DROP\s+FOREIGN\s+KEY/i.test(sql) && /ADD\s+CONSTRAINT\s+`?[\w$]+`?\s+FOREIGN\s+KEY/i.test(sql);
}
