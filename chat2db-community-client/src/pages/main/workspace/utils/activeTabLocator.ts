import { TreeNodeType, WorkspaceTabType } from '@/constants';
import { treeConfig } from '@/blocks/NewTree/treeConfig';
import type { IWorkspaceTab } from '@/typings';
import { getDirectActiveTabLocateTargets, type ExplorerActiveTabLocateTarget } from './activeTabTarget';

export {
  getActiveTabLocateTargetForPanel,
  resolveWorkspaceLeftPanel,
  type WorkspaceLeftPanel,
} from './activeTabTarget';

export interface ActiveTabDatabaseCandidate {
  key?: string;
  treeNodeType?: TreeNodeType;
  name?: string;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  fallback?: boolean;
}

export interface DatabaseActiveTabLocateTarget {
  surface: 'databaseTree';
  candidates: ActiveTabDatabaseCandidate[];
  loadPath: string[];
}

export interface ActiveTabLocateTargets {
  explorer?: ExplorerActiveTabLocateTarget;
  database?: DatabaseActiveTabLocateTarget;
}

export type ActiveTabLocateTarget = ExplorerActiveTabLocateTarget | DatabaseActiveTabLocateTarget;

function createTreeKey(treeNodeType: TreeNodeType, data: Record<string, unknown>) {
  return treeConfig[treeNodeType].createTreeNodeKey?.(data);
}

function compact<T>(values: Array<T | undefined>) {
  return values.filter((value): value is T => value !== undefined);
}

function keyCandidate(
  treeNodeType: TreeNodeType,
  data: Record<string, unknown>,
): ActiveTabDatabaseCandidate | undefined {
  const key = createTreeKey(treeNodeType, data);
  if (!key) {
    return undefined;
  }
  return { key, treeNodeType };
}

function fallbackKeyCandidate(
  treeNodeType: TreeNodeType,
  data: Record<string, unknown>,
): ActiveTabDatabaseCandidate | undefined {
  const candidate = keyCandidate(treeNodeType, data);
  if (!candidate) {
    return undefined;
  }
  return { ...candidate, fallback: true };
}

function objectCandidate(
  treeNodeType: TreeNodeType,
  uniqueData: IWorkspaceTab['uniqueData'],
  name?: string,
): ActiveTabDatabaseCandidate | undefined {
  if (!uniqueData?.dataSourceId || !name) {
    return undefined;
  }

  return {
    treeNodeType,
    name,
    dataSourceId: uniqueData.dataSourceId,
    databaseName: uniqueData.databaseName,
    schemaName: uniqueData.schemaName,
  };
}

function getContextCandidates(uniqueData: IWorkspaceTab['uniqueData']): ActiveTabDatabaseCandidate[] {
  if (!uniqueData?.dataSourceId) {
    return [];
  }

  const { dataSourceId, databaseName, schemaName } = uniqueData;
  return compact([
    schemaName
      ? fallbackKeyCandidate(TreeNodeType.SCHEMA, {
          dataSourceId,
          databaseName,
          schemaName,
        })
      : undefined,
    databaseName
      ? fallbackKeyCandidate(TreeNodeType.DATABASE, {
          dataSourceId,
          databaseName,
        })
      : undefined,
    fallbackKeyCandidate(TreeNodeType.DATA_SOURCE, { dataSourceId }),
  ]);
}

function getContextLoadPath(uniqueData: IWorkspaceTab['uniqueData']) {
  if (!uniqueData?.dataSourceId) {
    return [];
  }

  const { dataSourceId, databaseName, schemaName } = uniqueData;
  const dataSourceKey = createTreeKey(TreeNodeType.DATA_SOURCE, { dataSourceId });
  const databaseKey = databaseName ? createTreeKey(TreeNodeType.DATABASE, { dataSourceId, databaseName }) : undefined;
  const schemaKey = schemaName
    ? createTreeKey(TreeNodeType.SCHEMA, { dataSourceId, databaseName, schemaName })
    : undefined;

  return compact([dataSourceKey, databaseKey, schemaKey]);
}

function getObjectParentLoadPath(uniqueData: IWorkspaceTab['uniqueData'], parentTreeNodeType: TreeNodeType) {
  if (!uniqueData?.dataSourceId) {
    return [];
  }

  const { dataSourceId, databaseName, schemaName } = uniqueData;
  const parentKey = createTreeKey(parentTreeNodeType, {
    dataSourceId,
    databaseName,
    schemaName,
  });

  return compact([...getContextLoadPath(uniqueData), parentKey]);
}

function databaseTreeTarget(
  candidates: ActiveTabDatabaseCandidate[],
  loadPath: string[],
): DatabaseActiveTabLocateTarget | undefined {
  if (!candidates.length) {
    return undefined;
  }

  return {
    surface: 'databaseTree',
    candidates,
    loadPath,
  };
}

function getDatabaseObjectLocateTarget(activeTab: IWorkspaceTab): DatabaseActiveTabLocateTarget | undefined {
  const uniqueData = activeTab.uniqueData;
  if (!uniqueData?.dataSourceId) {
    return undefined;
  }

  const { dataSourceId, databaseName, schemaName } = uniqueData;
  const baseParams = {
    dataSourceId,
    databaseName,
    schemaName,
  };

  switch (activeTab.type) {
    case WorkspaceTabType.EditTable:
    case WorkspaceTabType.EditTableData:
      return databaseTreeTarget(
        compact([
          uniqueData.tableName
            ? keyCandidate(TreeNodeType.TABLE, {
                ...baseParams,
                tableName: uniqueData.tableName,
              })
            : undefined,
          objectCandidate(TreeNodeType.TABLE, uniqueData, uniqueData.tableName),
          ...getContextCandidates(uniqueData),
        ]),
        getObjectParentLoadPath(uniqueData, TreeNodeType.TABLES),
      );

    case WorkspaceTabType.ViewView:
    case WorkspaceTabType.VIEW: {
      const viewName = uniqueData.viewName || uniqueData.tableName;
      return databaseTreeTarget(
        compact([
          viewName
            ? keyCandidate(TreeNodeType.VIEW, {
                ...baseParams,
                tableName: viewName,
              })
            : undefined,
          objectCandidate(TreeNodeType.VIEW, uniqueData, viewName),
          fallbackKeyCandidate(TreeNodeType.VIEWS, baseParams),
          ...getContextCandidates(uniqueData),
        ]),
        getObjectParentLoadPath(uniqueData, TreeNodeType.VIEWS),
      );
    }

    case WorkspaceTabType.FUNCTION:
      return databaseTreeTarget(
        compact([
          objectCandidate(TreeNodeType.FUNCTION, uniqueData, uniqueData.functionName),
          fallbackKeyCandidate(TreeNodeType.FUNCTIONS, baseParams),
          ...getContextCandidates(uniqueData),
        ]),
        getObjectParentLoadPath(uniqueData, TreeNodeType.FUNCTIONS),
      );

    case WorkspaceTabType.PROCEDURE:
      return databaseTreeTarget(
        compact([
          objectCandidate(TreeNodeType.PROCEDURE, uniqueData, uniqueData.procedureName),
          fallbackKeyCandidate(TreeNodeType.PROCEDURES, baseParams),
          ...getContextCandidates(uniqueData),
        ]),
        getObjectParentLoadPath(uniqueData, TreeNodeType.PROCEDURES),
      );

    case WorkspaceTabType.TRIGGER:
      return databaseTreeTarget(
        compact([
          objectCandidate(TreeNodeType.TRIGGER, uniqueData, uniqueData.triggerName),
          fallbackKeyCandidate(TreeNodeType.TRIGGERS, baseParams),
          ...getContextCandidates(uniqueData),
        ]),
        getObjectParentLoadPath(uniqueData, TreeNodeType.TRIGGERS),
      );

    case WorkspaceTabType.ViewAllTable:
      return databaseTreeTarget(
        compact([keyCandidate(TreeNodeType.TABLES, baseParams), ...getContextCandidates(uniqueData)]),
        getContextLoadPath(uniqueData),
      );

    case WorkspaceTabType.ViewAllView:
      return databaseTreeTarget(
        compact([keyCandidate(TreeNodeType.VIEWS, baseParams), ...getContextCandidates(uniqueData)]),
        getContextLoadPath(uniqueData),
      );

    case WorkspaceTabType.RedisAllData:
      return databaseTreeTarget(
        compact([keyCandidate(TreeNodeType.ALL_DATA, baseParams), ...getContextCandidates(uniqueData)]),
        getContextLoadPath(uniqueData),
      );

    case WorkspaceTabType.AccountPrivileges:
      return databaseTreeTarget(
        compact([
          uniqueData.user
            ? keyCandidate(TreeNodeType.DATABASE_ACCOUNT, {
                dataSourceId,
                user: uniqueData.user,
                host: uniqueData.host || '',
              })
            : undefined,
          objectCandidate(TreeNodeType.DATABASE_ACCOUNT, uniqueData, uniqueData.user),
          fallbackKeyCandidate(TreeNodeType.DATABASE_ACCOUNTS, { dataSourceId }),
          ...getContextCandidates(uniqueData),
        ]),
        compact([
          createTreeKey(TreeNodeType.DATA_SOURCE, { dataSourceId }),
          createTreeKey(TreeNodeType.DATABASE_ACCOUNTS, { dataSourceId }),
        ]),
      );

    case WorkspaceTabType.CreateTable:
    case WorkspaceTabType.ViewERModal:
    case WorkspaceTabType.ChangeAiTableInfo:
      return databaseTreeTarget(getContextCandidates(uniqueData), getContextLoadPath(uniqueData));

    default:
      return undefined;
  }
}

export function getActiveTabLocateTargets(activeTab?: IWorkspaceTab | null): ActiveTabLocateTargets {
  if (!activeTab) {
    return {};
  }

  const directTargets = getDirectActiveTabLocateTargets(activeTab);
  if (directTargets !== undefined) {
    return {
      explorer: directTargets.explorer,
      database: directTargets.database
        ? databaseTreeTarget(getContextCandidates(activeTab.uniqueData), getContextLoadPath(activeTab.uniqueData))
        : undefined,
    };
  }

  return {
    database: getDatabaseObjectLocateTarget(activeTab),
  };
}
