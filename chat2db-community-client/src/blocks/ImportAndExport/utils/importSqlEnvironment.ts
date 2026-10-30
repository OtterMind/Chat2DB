import type { BoundInfo } from '@/components/SelectBoundInfo';
import { DatabaseCapability } from '@/constants/databaseCapabilities';
import { getDatabaseSupport, isDatabaseCapabilitySupported } from '@/utils/databaseJudgments';

/** A script runs in a namespace, never in a target table inherited from a data-file import. */
export const sqlImportEnvironmentTarget = (environment: BoundInfo) => ({
  dataSourceId: environment.dataSourceId,
  databaseName: environment.databaseName,
  schemaName: environment.schemaName,
  tableName: undefined,
});

export const isSqlImportEnvironmentReady = (environment: BoundInfo): boolean => {
  if (!environment.dataSourceId || !environment.databaseType ||
      !isDatabaseCapabilitySupported(environment.databaseType, DatabaseCapability.IMPORT_EXPORT)) return false;
  const { supportDatabase, supportSchema } = getDatabaseSupport(environment.databaseType);
  return (!supportDatabase || !!environment.databaseName) && (!supportSchema || !!environment.schemaName);
};
