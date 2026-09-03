import { WorkspaceTabType } from '@/constants';
import sqlService from '@/service/sql';
import { randomLargeLong } from '@/utils';
import { buildWorkspaceObjectTabTitle } from '@/utils/workspaceObjectTabTitle';

function getObjectTabId(prefix: WorkspaceTabType, extraParams: any, objectName: string) {
  return [
    prefix,
    extraParams.dataSourceId,
    extraParams.databaseName || '',
    extraParams.schemaName || '',
    objectName,
  ].join(':');
}

export const openView = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams, originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = buildWorkspaceObjectTabTitle({ dataSourceName, databaseName, schemaName, objectName: originalTitle });
  addWorkspaceTab({
    id: randomLargeLong(),
    title,
    type: WorkspaceTabType.ViewView,
    uniqueData: {
      ...extraParams,
      popoverContent: title,
    },
  });
};
export const editView = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams, originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = buildWorkspaceObjectTabTitle({ dataSourceName, databaseName, schemaName, objectName: originalTitle });
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.VIEW, extraParams, originalTitle),
    type: WorkspaceTabType.VIEW,
    title,
    uniqueData: {
      ...extraParams,
      tableName: originalTitle,
      viewName: originalTitle,
      loadSQL: () => {
        return new Promise((resolve) => {
          sqlService
            .getViewDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              schemaName: treeNodeData.extraParams?.schemaName,
              tableName: treeNodeData.originalTitle,
            } as any)
            .then((res) => {
              // Update the DDL.
              resolve(res.ddl);
            });
        });
      },
      popoverContent: title,
    },
  });
};

export const openFunction = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams, originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = buildWorkspaceObjectTabTitle({ dataSourceName, databaseName, schemaName, objectName: originalTitle });
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.FUNCTION, extraParams, originalTitle),
    type: WorkspaceTabType.FUNCTION,
    title,
    uniqueData: {
      ...extraParams,
      functionName: originalTitle,
      loadSQL: () => {
        return new Promise((resolve) => {
          sqlService
            .getFunctionDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              schemaName: treeNodeData.extraParams?.schemaName,
              functionName: treeNodeData.originalTitle,
            } as any)
            .then((res) => {
              // Update the DDL.
              resolve(res.functionBody);
            });
        });
      },
      popoverContent: title,
    },
  });
};

export const openProcedure = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams, originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = buildWorkspaceObjectTabTitle({ dataSourceName, databaseName, schemaName, objectName: originalTitle });
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.PROCEDURE, extraParams, originalTitle),
    type: WorkspaceTabType.PROCEDURE,
    title,
    uniqueData: {
      ...extraParams,
      procedureName: originalTitle,
      loadSQL: () => {
        return new Promise((resolve) => {
          sqlService
            .getProcedureDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              schemaName: treeNodeData.extraParams?.schemaName,
              procedureName: treeNodeData.originalTitle,
            } as any)
            .then((res) => {
              // Update the DDL.
              resolve(res.procedureBody);
            });
        });
      },
      popoverContent: title,
    },
  });
};

export const openTrigger = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams, originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const title = buildWorkspaceObjectTabTitle({ dataSourceName, databaseName, schemaName, objectName: originalTitle });
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.TRIGGER, extraParams, originalTitle),
    type: WorkspaceTabType.TRIGGER,
    title,
    uniqueData: {
      ...extraParams,
      triggerName: originalTitle,
      loadSQL: () => {
        return new Promise((resolve) => {
          sqlService
            .getTriggerDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseType: treeNodeData.extraParams!.databaseType!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              schemaName: treeNodeData.extraParams?.schemaName,
              triggerName: treeNodeData.originalTitle,
            } as any)
            .then((res) => {
              // Update the DDL.
              resolve(res.triggerBody);
            });
        });
      },
      popoverContent: title,
    },
  });
};

export const openCreateEvent = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const eventName = 'new_event';
  const title = buildWorkspaceObjectTabTitle({ dataSourceName, databaseName, schemaName, objectName: eventName });
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.EVENT, extraParams, eventName),
    type: WorkspaceTabType.EVENT,
    title,
    uniqueData: {
      ...extraParams,
      eventName,
      isNewObject: true,
      ddl: `CREATE EVENT \`new_event\`
ON SCHEDULE EVERY 1 DAY
STARTS CURRENT_TIMESTAMP
DO
BEGIN
  -- event body
END;`,
      popoverContent: title,
    },
  });
};

export const openEvent = (props: { treeNodeData: any; addWorkspaceTab: any }) => {
  const { treeNodeData, addWorkspaceTab } = props;
  const { extraParams, originalTitle } = treeNodeData;
  const { databaseName, schemaName, dataSourceName } = extraParams;
  const eventName = extraParams.eventName || originalTitle;
  const title = buildWorkspaceObjectTabTitle({ dataSourceName, databaseName, schemaName, objectName: eventName });
  addWorkspaceTab({
    id: getObjectTabId(WorkspaceTabType.EVENT, extraParams, eventName),
    type: WorkspaceTabType.EVENT,
    title,
    uniqueData: {
      ...extraParams,
      eventName,
      loadSQL: () => {
        return new Promise((resolve) => {
          sqlService
            .getEventDetail({
              dataSourceId: treeNodeData.extraParams!.dataSourceId!,
              databaseName: treeNodeData.extraParams!.databaseName!,
              eventName,
            })
            .then((res) => {
              resolve(res.eventBody);
            });
        });
      },
      popoverContent: title,
    },
  });
};
