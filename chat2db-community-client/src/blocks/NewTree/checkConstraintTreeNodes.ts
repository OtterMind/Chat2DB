import { DatabaseTypeCode } from '@/constants/common';
import { TreeNodeType } from '@/constants/tree';
import type { ICheckConstraintItem } from '@/typings/editTable';
import type { TreeNodeData } from '@/typings/tree';
import { isMysqlCheckConstraintsSupported } from '@/utils/mysqlCheckConstraints';

export type LoadCheckConstraintTableDetails = (params: {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
  refresh: boolean;
}) => Promise<{
  dbVersion?: string | null;
  checkConstraintList?: ICheckConstraintItem[];
} | null | undefined>;

export function createCheckConstraintsTreeNodeKey(params: any) {
  const { dataSourceId, databaseName, schemaName, tableName } = params;
  return [
    `dataSource_${dataSourceId}`,
    databaseName ? `database_${databaseName}` : '',
    schemaName ? `schema_${schemaName}` : '',
    `table_${tableName}`,
    'checkConstraints_chat2dbCatalogue',
  ].join('-');
}

export function createCheckConstraintTreeNodeKey(params: any) {
  const { dataSourceId, databaseName, schemaName, tableName, checkConstraintName } = params;
  return [
    `dataSource_${dataSourceId}`,
    databaseName ? `database_${databaseName}` : '',
    schemaName ? `schema_${schemaName}` : '',
    `table_${tableName}`,
    `checkConstraint_${checkConstraintName}`,
  ].join('-');
}

export function createCheckConstraintNodes(extraParams: any, constraints: ICheckConstraintItem[]): TreeNodeData[] {
  const { dataSourceId, databaseName, schemaName, tableName } = extraParams;
  return constraints.map((constraint) => ({
    key: createCheckConstraintTreeNodeKey({
      dataSourceId,
      databaseName,
      schemaName,
      tableName,
      checkConstraintName: constraint.name,
    }),
    originalTitle: constraint.name,
    title: null,
    treeNodeType: TreeNodeType.CHECK_CONSTRAINT,
    isLeaf: true,
    describe: constraint.expression,
    extraParams: {
      ...extraParams,
      checkConstraintName: constraint.name,
      checkExpression: constraint.expression,
      checkEnforced: constraint.enforced !== false,
    },
    decorativeParams: {
      expression: constraint.expression,
      enforced: constraint.enforced !== false,
    },
  }));
}

export async function loadCheckConstraintNodes(
  extraParams: any,
  getTableDetails: LoadCheckConstraintTableDetails,
): Promise<TreeNodeData[]> {
  const { dataSourceId, databaseName, schemaName, tableName, databaseType } = extraParams;
  if (databaseType !== DatabaseTypeCode.MYSQL) {
    return [];
  }
  const tableDetails = await getTableDetails({
    dataSourceId,
    databaseName,
    schemaName,
    tableName,
    refresh: true,
  });
  const constraints = tableDetails?.checkConstraintList || [];
  if (!isMysqlCheckConstraintsSupported(databaseType, tableDetails?.dbVersion)) {
    return [];
  }
  return createCheckConstraintNodes(extraParams, constraints);
}
