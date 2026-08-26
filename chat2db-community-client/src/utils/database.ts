import { DatabaseTypeCode, NoSQLDataBaseType } from '@/constants/common';
import { SqlTypeEnum } from '@/typings/sqlParser';
import { TreeNodeType } from '@/constants';
import { getDatabaseSupport as getDatabaseJudgmentSupport, quoteOpenTableIdentifier } from './databaseJudgments';
export { processResultDataList } from './resultData';

/**
 * Compatible with processing database names
 * @param databaseName
 * @param databaseType
 * @returns
 */
export function compatibleDataBaseName(databaseName: string, databaseType: DatabaseTypeCode) {
  return quoteOpenTableIdentifier(databaseName, databaseType);
}

export const getDatabaseSupport = (databaseType?: DatabaseTypeCode) => {
  return getDatabaseJudgmentSupport(databaseType);
};

/**
 * Whether it is a NoSQL database
 * @param databaseType
 * @returns
 */
export const isNoSQL = (databaseType?: DatabaseTypeCode) => {
  if (!databaseType) {
    return false;
  }
  return !!NoSQLDataBaseType[databaseType];
};

// Mapping of execution result type and refresh tree node
export const resultAndTreeNodeMap = (sqlType: SqlTypeEnum, databaseType: DatabaseTypeCode) => {
  const { supportDatabase } = getDatabaseSupport(databaseType);
  let treeNodeType: TreeNodeType | null = null;
  switch (sqlType) {
    case SqlTypeEnum.CREATE_DATABASE:
    case SqlTypeEnum.DROP_DATABASE:
      treeNodeType = TreeNodeType.DATA_SOURCE;
      break;
    case SqlTypeEnum.CREATE_SCHEMA:
    case SqlTypeEnum.DROP_SCHEMA:
      treeNodeType = supportDatabase ? TreeNodeType.DATABASE : TreeNodeType.DATA_SOURCE;
      break;
    case SqlTypeEnum.CREATE_TABLE:
    case SqlTypeEnum.DROP_TABLE:
      treeNodeType = TreeNodeType.TABLES;
      break;
    case SqlTypeEnum.CREATE_COLUMN:
    case SqlTypeEnum.DROP_COLUMN:
      treeNodeType = TreeNodeType.COLUMNS;
      break;
    case SqlTypeEnum.CREATE_VIEW:
    case SqlTypeEnum.DROP_VIEW:
      treeNodeType = TreeNodeType.VIEWS;
      break;
    case SqlTypeEnum.CREATE_FUNCTION:
    case SqlTypeEnum.DROP_FUNCTION:
      treeNodeType = TreeNodeType.FUNCTIONS;
      break;
    case SqlTypeEnum.CREATE_PROCEDURE:
    case SqlTypeEnum.DROP_PROCEDURE:
      treeNodeType = TreeNodeType.PROCEDURES;
      break;
    case SqlTypeEnum.CREATE_TRIGGER:
    case SqlTypeEnum.DROP_TRIGGER:
      treeNodeType = TreeNodeType.TRIGGERS;
      break;
    default:
      break;
  }
  return treeNodeType;
};
