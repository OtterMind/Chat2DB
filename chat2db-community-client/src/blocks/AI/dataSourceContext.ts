import type { IExtraParams, TreeNodeData } from '@/typings';

export type AIDataSourceContext = Pick<
  IExtraParams,
  | 'dataSourceId'
  | 'dataSourceName'
  | 'databaseType'
  | 'environmentId'
  | 'environment'
  | 'identityColor'
  | 'watermarkEnabled'
  | 'watermarkContent'
>;

export function resolveAIDataSourceContext(
  dataSourceList: TreeNodeData[] | null | undefined,
  dataSourceId: number | undefined,
): AIDataSourceContext | undefined {
  if (dataSourceId === undefined) {
    return undefined;
  }

  const dataSource = dataSourceList?.find((item) => item.extraParams?.dataSourceId === dataSourceId);
  if (!dataSource) {
    return undefined;
  }

  const { extraParams } = dataSource;
  return {
    dataSourceId,
    dataSourceName: extraParams.dataSourceName,
    databaseType: extraParams.databaseType,
    environmentId: extraParams.environmentId ?? extraParams.environment?.id ?? null,
    environment: extraParams.environment ?? null,
    identityColor: extraParams.identityColor ?? null,
    watermarkEnabled: extraParams.watermarkEnabled ?? null,
    watermarkContent: extraParams.watermarkContent ?? null,
  };
}
