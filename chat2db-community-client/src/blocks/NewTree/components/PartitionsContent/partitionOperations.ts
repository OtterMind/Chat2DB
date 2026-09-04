import type { IDdlExecuteRequest } from '@/service/dmlRequest';
import { DatabaseTypeCode } from '@/constants/common';

export interface PartitionOperationContext {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string | null;
  tableName: string;
}

export const RANGE_LIST_METHODS = ['RANGE', 'RANGE COLUMNS', 'LIST', 'LIST COLUMNS'];
export const HASH_KEY_METHODS = ['HASH', 'LINEAR HASH', 'KEY', 'LINEAR KEY'];
export const PARTITION_READBACK_FIELD_KEYS = [
  'partitionName',
  'subpartitionName',
  'ordinalPosition',
  'subpartitionOrdinalPosition',
  'method',
  'subpartitionMethod',
  'expression',
  'subpartitionExpression',
  'description',
  'tableRows',
  'avgRowLength',
  'dataLength',
  'maxDataLength',
  'indexLength',
  'dataFree',
  'createTime',
  'updateTime',
  'checkTime',
  'checksum',
  'comment',
  'nodegroup',
  'tablespaceName',
] as const;

export interface PartitionOperationAvailability {
  add: boolean;
  drop: boolean;
  truncate: boolean;
  reorganize: boolean;
  coalesce: boolean;
  maintain: boolean;
}

export function canInspectMysqlPartitions(databaseType?: DatabaseTypeCode | string | null): boolean {
  return databaseType === DatabaseTypeCode.MYSQL;
}

export function normalizePartitionMethod(method?: string | null) {
  return (method || '').trim().toUpperCase();
}

export function getPartitionOperationAvailability(method?: string | null): PartitionOperationAvailability {
  const normalized = normalizePartitionMethod(method);
  const isRangeList = RANGE_LIST_METHODS.includes(normalized);
  const isHashKey = HASH_KEY_METHODS.includes(normalized);
  return {
    add: isRangeList || isHashKey,
    drop: isRangeList,
    truncate: isRangeList,
    reorganize: isRangeList,
    coalesce: isHashKey,
    maintain: isRangeList || isHashKey,
  };
}

export function defaultPartitionDefinition(method?: string | null) {
  return normalizePartitionMethod(method).includes('LIST') ? 'VALUES IN (...)' : 'VALUES LESS THAN (...)';
}

export function defaultReorganizePartitionDefinitions(method?: string | null) {
  return normalizePartitionMethod(method).includes('LIST')
    ? 'PARTITION p_new VALUES IN (...), PARTITION p_next VALUES IN (...)'
    : 'PARTITION p_new VALUES LESS THAN (...), PARTITION p_next VALUES LESS THAN (...)';
}

export function isPartitionDropConfirmationValid(partitionName: string, confirmedName: string) {
  return partitionName === confirmedName.trim();
}

export function buildPartitionDdlExecuteRequest(
  context: PartitionOperationContext,
  sql: string,
): IDdlExecuteRequest {
  const request: IDdlExecuteRequest = {
    dataSourceId: context.dataSourceId,
    sql,
    tableName: context.tableName,
  };
  if (context.databaseName !== undefined) {
    request.databaseName = context.databaseName;
  }
  if (context.schemaName !== undefined) {
    request.schemaName = context.schemaName;
  }
  return request;
}

export async function executePartitionPreviewSql({
  context,
  sql,
  executeDDL,
  refresh,
}: {
  context: PartitionOperationContext;
  sql: string;
  executeDDL: (request: IDdlExecuteRequest) => Promise<unknown>;
  refresh: () => void | Promise<void>;
}) {
  await executeDDL(buildPartitionDdlExecuteRequest(context, sql));
  await refresh();
}
