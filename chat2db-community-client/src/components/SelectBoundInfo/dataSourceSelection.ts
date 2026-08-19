import type { DatabaseTypeCode } from '@/constants';
import type { IConnectionEnv } from '@/typings';

interface CachedDataSourceSelectionInput {
  dataSourceId?: number;
  dataSourceName?: string;
  environmentId?: number | null;
  environment?: IConnectionEnv | null;
  identityColor?: string | null;
  watermarkEnabled?: boolean | null;
  watermarkContent?: string | null;
  databaseType?: DatabaseTypeCode;
}

export function createCachedDataSourceSelection(boundInfo: CachedDataSourceSelectionInput) {
  return {
    value: boundInfo.dataSourceId?.toString() || '',
    label: boundInfo.dataSourceName?.trim() || '',
    title: boundInfo.dataSourceName,
    dataSourceId: boundInfo.dataSourceId,
    environmentId: boundInfo.environmentId,
    environment: boundInfo.environment,
    identityColor: boundInfo.identityColor,
    watermarkEnabled: boundInfo.watermarkEnabled,
    watermarkContent: boundInfo.watermarkContent,
    databaseType: boundInfo.databaseType,
  };
}
