import { WorkspaceTabType } from '@/constants';
import sqlService from '@/service/sql';
import { randomLargeLong } from '@/utils';

function getObjectTabId(prefix: WorkspaceTabType, extraParams: any, objectName: string) {
  return [prefix, extraParams.dataSourceId, extraParams.databaseName || '', extraParams.schemaName || '', objectName].join(':');
}

export const openView = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams,originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = [originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  const popoverContent =
    [databaseName, schemaName, originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  addWorkspaceTab({
    id: randomLargeLong(),
    title,
    type: WorkspaceTabType.ViewView,
    uniqueData: {
      ...extraParams,
      popoverContent
    },
  });
};
export const editView = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams,originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = [originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  const popoverContent =
    [databaseName, schemaName, originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.VIEW, extraParams, originalTitle),
    type: WorkspaceTabType.VIEW,
    title,
    uniqueData: {
      ...extraParams,
      tableName: originalTitle,
      viewName: originalTitle,
      loadSQL: () => {
        return sqlService
            .getViewDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              schemaName: treeNodeData.extraParams?.schemaName,
              tableName: treeNodeData.originalTitle,
            } as any)
            .then((res) => res.ddl);
      },
      popoverContent,
    },
  });
};

export const openFunction = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams,originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = [originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  const popoverContent =
    [databaseName, schemaName, originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.FUNCTION, extraParams, originalTitle),
    type: WorkspaceTabType.FUNCTION,
    title,
    uniqueData: {
      ...extraParams,
      functionName: originalTitle,
      loadSQL: () => {
        return sqlService
            .getFunctionDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              schemaName: treeNodeData.extraParams?.schemaName,
              functionName: treeNodeData.originalTitle,
            } as any)
            .then((res) => res.functionBody);
      },
      popoverContent
    },
  });
};

export const openProcedure = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams,originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = [originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  const popoverContent =
    [databaseName, schemaName, originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.PROCEDURE, extraParams, originalTitle),
    type: WorkspaceTabType.PROCEDURE,
    title,
    uniqueData: {
      ...extraParams,
      procedureName: originalTitle,
      loadSQL: () => {
        return sqlService
            .getProcedureDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              schemaName: treeNodeData.extraParams?.schemaName,
              procedureName: treeNodeData.originalTitle,
            } as any)
            .then((res) => res.procedureBody);
      },
      popoverContent
    },
  });
};

export const openTrigger = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams,originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = [originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  const popoverContent =
    [databaseName, schemaName, originalTitle].filter(Boolean).join('.') + `[${dataSourceName}]`;
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.TRIGGER, extraParams, originalTitle),
    type: WorkspaceTabType.TRIGGER,
    title,
    uniqueData: {
      ...extraParams,
      triggerName: originalTitle,
      loadSQL: () => {
        return sqlService
            .getTriggerDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              schemaName: treeNodeData.extraParams?.schemaName,
              triggerName: treeNodeData.originalTitle,
            } as any)
            .then((res) => res.triggerBody);
      },
      popoverContent
    },
  });
};
