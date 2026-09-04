import type { DatabaseTypeCode } from '@/constants';
import type { IBoundInfo } from '@/typings';

export interface DataSourceExecutionSnapshot {
  consoleId?: number;
  dataSourceId?: number;
  dataSourceName?: string;
  environmentId?: number;
  environmentName?: string;
  environmentShortName?: string;
  databaseName?: string;
  schemaName?: string;
  databaseType?: DatabaseTypeCode;
  connectable?: boolean;
  startedAt: number;
}

export type DataSourceExecutionTarget = Pick<
  DataSourceExecutionSnapshot,
  | 'consoleId'
  | 'dataSourceId'
  | 'dataSourceName'
  | 'environmentId'
  | 'environmentName'
  | 'environmentShortName'
  | 'databaseName'
  | 'schemaName'
  | 'databaseType'
  | 'connectable'
>;

export interface DataSourceExecutionSnapshotRegistry {
  snapshotsBySequence: Map<number, DataSourceExecutionSnapshot>;
  sequenceByExecutionId: Map<string, number>;
}

export function createDataSourceExecutionSnapshotRegistry(): DataSourceExecutionSnapshotRegistry {
  return {
    snapshotsBySequence: new Map(),
    sequenceByExecutionId: new Map(),
  };
}

export function createDataSourceExecutionBoundInfo(boundInfo: IBoundInfo): IBoundInfo {
  return Object.freeze({
    ...boundInfo,
    environment: boundInfo.environment
      ? Object.freeze({ ...boundInfo.environment })
      : boundInfo.environment,
  });
}

export function createDataSourceExecutionSnapshot(
  boundInfo: IBoundInfo,
  startedAt = Date.now(),
): DataSourceExecutionSnapshot {
  return Object.freeze({
    consoleId: boundInfo.consoleId,
    dataSourceId: boundInfo.dataSourceId,
    dataSourceName: boundInfo.dataSourceName,
    environmentId: boundInfo.environmentId ?? boundInfo.environment?.id,
    environmentName: boundInfo.environment?.name,
    environmentShortName: boundInfo.environment?.shortName,
    databaseName: boundInfo.databaseName,
    schemaName: boundInfo.schemaName,
    databaseType: boundInfo.databaseType,
    connectable: boundInfo.connectable,
    startedAt,
  });
}

export function registerDataSourceExecutionSnapshot(
  registry: DataSourceExecutionSnapshotRegistry,
  executionSequence: number,
  snapshot: DataSourceExecutionSnapshot,
): DataSourceExecutionSnapshot {
  registry.snapshotsBySequence.set(executionSequence, snapshot);
  return snapshot;
}

export function captureDataSourceExecutionSnapshot(
  registry: DataSourceExecutionSnapshotRegistry,
  executionSequence: number,
  boundInfo: IBoundInfo,
  startedAt = Date.now(),
): DataSourceExecutionSnapshot {
  return registerDataSourceExecutionSnapshot(
    registry,
    executionSequence,
    createDataSourceExecutionSnapshot(boundInfo, startedAt),
  );
}

export function attachDataSourceExecutionId(
  registry: DataSourceExecutionSnapshotRegistry,
  executionSequence: number,
  executionId: string,
): DataSourceExecutionSnapshot | undefined {
  const snapshot = registry.snapshotsBySequence.get(executionSequence);
  if (!snapshot) {
    return undefined;
  }
  registry.sequenceByExecutionId.set(executionId, executionSequence);
  return snapshot;
}

export function getDataSourceExecutionSnapshot(
  registry: DataSourceExecutionSnapshotRegistry,
  identity: { executionSequence?: number; executionId?: string },
): DataSourceExecutionSnapshot | undefined {
  const executionSequence =
    identity.executionSequence ??
    (identity.executionId ? registry.sequenceByExecutionId.get(identity.executionId) : undefined);
  return executionSequence === undefined ? undefined : registry.snapshotsBySequence.get(executionSequence);
}

export function releaseDataSourceExecutionSnapshot(
  registry: DataSourceExecutionSnapshotRegistry,
  identity: { executionSequence?: number; executionId?: string },
) {
  const executionSequence =
    identity.executionSequence ??
    (identity.executionId ? registry.sequenceByExecutionId.get(identity.executionId) : undefined);
  if (identity.executionId) {
    registry.sequenceByExecutionId.delete(identity.executionId);
  }
  if (executionSequence === undefined) {
    return;
  }
  registry.snapshotsBySequence.delete(executionSequence);
  registry.sequenceByExecutionId.forEach((sequence, executionId) => {
    if (sequence === executionSequence) {
      registry.sequenceByExecutionId.delete(executionId);
    }
  });
}

export function getDataSourceExecutionTargetLabel(target?: DataSourceExecutionTarget | null) {
  if (!target) {
    return '';
  }
  return [
    target.environmentShortName?.trim() || target.environmentName?.trim(),
    target.dataSourceName?.trim(),
    target.databaseName?.trim(),
    target.schemaName?.trim(),
  ]
    .filter((value): value is string => !!value)
    .join(' / ');
}
