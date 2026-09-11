import clientExtension from '@client-extension';
import type { TreeNodeData } from '@/typings';
import type {
  ResourceOperation,
  ResourceOperationCapabilities,
  ResourceOperationRequest,
} from './types';

export const RESOURCE_OPERATIONS: readonly ResourceOperation[] = [
  'SELECT',
  'INSERT',
  'UPDATE',
  'DELETE',
  'CREATE',
  'ALTER',
  'DROP',
  'TRUNCATE',
];

const CACHE_TTL_MS = 5_000;

interface CacheEntry {
  expiresAt: number;
  promise: Promise<ResourceOperationCapabilities>;
}

const cache = new Map<string, CacheEntry>();

const capabilities = (
  allowed: boolean,
  operations: readonly ResourceOperation[] = RESOURCE_OPERATIONS,
): ResourceOperationCapabilities =>
  Object.fromEntries(operations.map((operation) => [operation, allowed])) as ResourceOperationCapabilities;

const currentOrganizationId = (): string => {
  if (typeof document === 'undefined') {
    return '';
  }
  return document.cookie.match(/(?:^|;\s*)Chat2db-Organization-Id=([^;]*)/)?.[1] || '';
};

const requestForNode = (node: TreeNodeData): ResourceOperationRequest | null => {
  const { dataSourceId, databaseType, databaseName, schemaName, tableName } = node.extraParams || {};
  if (!dataSourceId) {
    return null;
  }
  return {
    dataSourceId,
    dbType: databaseType,
    databaseName,
    schemaName,
    tableName,
    operationTypes: RESOURCE_OPERATIONS,
  };
};

const cacheKey = (request: ResourceOperationRequest): string =>
  [
    currentOrganizationId(),
    request.dataSourceId,
    request.dbType,
    request.databaseName,
    request.schemaName,
    request.tableName,
  ]
    .map((value) => value || '')
    .join('\u0000');

const validateCapabilities = (
  received: ResourceOperationCapabilities,
  operations: readonly ResourceOperation[],
): ResourceOperationCapabilities => {
  if (!received || operations.some((operation) => typeof received[operation] !== 'boolean')) {
    throw new Error('Resource operation authorization returned an incomplete decision set.');
  }
  return received;
};

export const loadResourceOperationCapabilities = async (
  node: TreeNodeData,
): Promise<ResourceOperationCapabilities | undefined> => {
  const authorize = clientExtension.resourceOperations;
  if (!authorize) {
    return undefined;
  }
  const request = requestForNode(node);
  if (!request) {
    return capabilities(false);
  }
  const key = cacheKey(request);
  const now = Date.now();
  const cached = cache.get(key);
  if (cached && cached.expiresAt > now) {
    return cached.promise;
  }

  const promise = authorize(request)
    .then((received) => validateCapabilities(received, request.operationTypes))
    .catch((error) => {
      console.warn('Unable to authorize resource operations; write operations are hidden.', error);
      return capabilities(false, request.operationTypes);
    });
  cache.set(key, { expiresAt: now + CACHE_TTL_MS, promise });
  return promise;
};

export const allowsResourceOperations = (
  snapshot: ResourceOperationCapabilities | undefined,
  required: readonly ResourceOperation[] | undefined,
): boolean => {
  if (!required?.length || !clientExtension.resourceOperations) {
    return true;
  }
  return !!snapshot && required.every((operation) => snapshot[operation] === true);
};

export const invalidateResourceOperationCapabilities = (): void => {
  cache.clear();
};
