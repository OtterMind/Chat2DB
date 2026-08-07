import { useWorkspaceStore } from '@/store/workspace';
import { useTreeStore } from '@/store/tree';

export const handelCreateConsole = () => {
  const currentTreeNode = useTreeStore.getState().currentTreeNode;
  const dataSourceList = useTreeStore.getState().dataSourceList;
  const createConsole = useWorkspaceStore.getState().createConsole;

  if (currentTreeNode?.extraParams?.dataSourceId) {
    const param = {
      dataSourceId: currentTreeNode.extraParams.dataSourceId,
      dataSourceName: currentTreeNode.extraParams.dataSourceName!,
      environmentId: currentTreeNode.extraParams.environmentId,
      environment: currentTreeNode.extraParams.environment,
      identityColor: currentTreeNode.extraParams.identityColor,
      watermarkEnabled: currentTreeNode.extraParams.watermarkEnabled,
      watermarkContent: currentTreeNode.extraParams.watermarkContent,
      databaseType: currentTreeNode.extraParams.databaseType!,
      databaseName: currentTreeNode.extraParams.databaseName,
      schemaName: currentTreeNode.extraParams.schemaName,
    };
    createConsole(param);
  } else if (dataSourceList?.[0]?.extraParams) {
    const param: any = {
      dataSourceId: dataSourceList[0].extraParams.dataSourceId,
      dataSourceName: dataSourceList[0].extraParams.dataSourceName,
      environmentId: dataSourceList[0].extraParams.environmentId,
      environment: dataSourceList[0].extraParams.environment,
      identityColor: dataSourceList[0].extraParams.identityColor,
      watermarkEnabled: dataSourceList[0].extraParams.watermarkEnabled,
      watermarkContent: dataSourceList[0].extraParams.watermarkContent,
      databaseType: dataSourceList[0].extraParams.databaseType,
    };
    createConsole(param);
  }
};
