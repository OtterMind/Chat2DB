import { DatabaseTypeCode } from '@/constants/common';
import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings/tree';
import {
  beginLatestRequest,
  invalidateLatestRequest,
  isLatestRequest,
  type RequestGenerationRef,
} from '@/utils/latestRequest';

export interface SelectDatabaseOption {
  value: string;
  label: string;
}

export interface SelectDataSourceOption {
  value: number;
  label: string;
  databaseType: DatabaseTypeCode;
}

interface LatestOptionRequest {
  active: boolean;
  generationRef: RequestGenerationRef;
}

export interface SelectDatabaseRequestLifecycle {
  database: LatestOptionRequest;
  schema: LatestOptionRequest;
}

export function hasApplicableDatabaseNameChange(changedValues: { databaseName?: string }, supportDatabase?: boolean) {
  return supportDatabase !== false && 'databaseName' in changedValues;
}

function createLatestOptionRequest(): LatestOptionRequest {
  return {
    active: true,
    generationRef: { current: 0 },
  };
}

export function createSelectDatabaseRequestLifecycle(): SelectDatabaseRequestLifecycle {
  return {
    database: createLatestOptionRequest(),
    schema: createLatestOptionRequest(),
  };
}

function activateLatestOptionRequest(request: LatestOptionRequest) {
  request.active = true;
}

function invalidateLatestOptionRequest(request: LatestOptionRequest) {
  invalidateLatestRequest(request.generationRef);
}

function disposeLatestOptionRequest(request: LatestOptionRequest) {
  request.active = false;
  invalidateLatestOptionRequest(request);
}

export function activateSelectDatabaseRequests(lifecycle: SelectDatabaseRequestLifecycle) {
  activateLatestOptionRequest(lifecycle.database);
  activateLatestOptionRequest(lifecycle.schema);
}

export function invalidateDataSourceOptionRequests(lifecycle: SelectDatabaseRequestLifecycle) {
  invalidateLatestOptionRequest(lifecycle.database);
  invalidateLatestOptionRequest(lifecycle.schema);
}

export function invalidateDatabaseOptionRequests(lifecycle: SelectDatabaseRequestLifecycle) {
  invalidateLatestOptionRequest(lifecycle.schema);
}

export function disposeSelectDatabaseRequests(lifecycle: SelectDatabaseRequestLifecycle) {
  disposeLatestOptionRequest(lifecycle.database);
  disposeLatestOptionRequest(lifecycle.schema);
}

async function runLatestOptionRequest<T>(
  request: LatestOptionRequest,
  load: () => Promise<T>,
  onSuccess: (value: T) => void,
  onError: () => void,
) {
  if (!request.active) {
    return false;
  }

  const generation = beginLatestRequest(request.generationRef);
  try {
    const value = await load();
    if (!request.active || !isLatestRequest(request.generationRef, generation)) {
      return false;
    }
    onSuccess(value);
    return true;
  } catch {
    if (!request.active || !isLatestRequest(request.generationRef, generation)) {
      return false;
    }
    onError();
    return true;
  }
}

export function runDatabaseOptionRequest<T>(
  lifecycle: SelectDatabaseRequestLifecycle,
  load: () => Promise<T>,
  onSuccess: (value: T) => void,
  onError: () => void,
) {
  return runLatestOptionRequest(lifecycle.database, load, onSuccess, onError);
}

export function runSchemaOptionRequest<T>(
  lifecycle: SelectDatabaseRequestLifecycle,
  load: () => Promise<T>,
  onSuccess: (value: T) => void,
  onError: () => void,
) {
  return runLatestOptionRequest(lifecycle.schema, load, onSuccess, onError);
}

export function normalizeDataSourceOptions(nodes: TreeNodeData[]): SelectDataSourceOption[] {
  return nodes.reduce<SelectDataSourceOption[]>((options, node) => {
    const dataSourceId = node.extraParams?.dataSourceId;
    const databaseType = node.extraParams?.databaseType;
    if (node.treeNodeType !== TreeNodeType.DATA_SOURCE || dataSourceId === undefined || !databaseType) {
      return options;
    }

    options.push({
      value: dataSourceId,
      label: node.originalTitle,
      databaseType,
    });
    return options;
  }, []);
}

function normalizeNamedOptions(
  nodes: TreeNodeData[],
  expectedNodeType: TreeNodeType,
  field: 'databaseName' | 'schemaName',
): SelectDatabaseOption[] {
  const seenValues = new Set<string>();

  return nodes.reduce<SelectDatabaseOption[]>((options, node) => {
    const value = node.extraParams?.[field];
    if (node.treeNodeType !== expectedNodeType || typeof value !== 'string' || !value.trim() || seenValues.has(value)) {
      return options;
    }

    seenValues.add(value);
    options.push({
      value,
      label: node.originalTitle?.trim() || value,
    });
    return options;
  }, []);
}

export function normalizeDatabaseOptions(nodes: TreeNodeData[]) {
  return normalizeNamedOptions(nodes, TreeNodeType.DATABASE, 'databaseName');
}

export function normalizeSchemaOptions(nodes: TreeNodeData[]) {
  return normalizeNamedOptions(nodes, TreeNodeType.SCHEMA, 'schemaName');
}
