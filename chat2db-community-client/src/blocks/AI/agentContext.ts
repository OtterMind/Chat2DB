import type { IBoundInfo } from '@/typings/workspace';
import type { AgentContextScope, AgentContextObject, AgentRunContextRequest } from '@/types/agentContext';

type DatabaseSelection = Pick<IBoundInfo, 'dataSourceId' | 'dataSourceName' | 'databaseType' | 'databaseName' | 'schemaName'>;

export const contextScope = (selection?: DatabaseSelection | null): AgentContextScope | null => {
  if (!selection?.dataSourceId) return null;
  const scope: AgentContextScope = {
    dataSourceId: String(selection.dataSourceId),
    database: selection.databaseName || null,
    schema: selection.schemaName || null,
  };
  if (selection.dataSourceName) scope.dataSourceName = selection.dataSourceName;
  if (selection.databaseType) scope.databaseType = String(selection.databaseType);
  return scope;
};

const sameScope = (left: AgentContextScope, right: AgentContextScope) =>
  left.dataSourceId === right.dataSourceId && left.database === right.database && left.schema === right.schema;

export const captureAgentContext = (
  selection: DatabaseSelection | null | undefined,
  currentTable: IBoundInfo | undefined,
  mentions: readonly AgentContextObject[],
  timeZone = new Intl.DateTimeFormat().resolvedOptions().timeZone,
): AgentRunContextRequest => {
  const scope = contextScope(selection);
  const tableScope = contextScope(currentTable);
  const objects = mentions.filter((object) => scope && sameScope(object, scope)).map((object) => ({ ...object }));
  const name = currentTable?.viewName || currentTable?.tableName;
  if (scope && tableScope && sameScope(scope, tableScope) && name) {
    const type = currentTable?.viewName ? 'VIEW' : 'TABLE';
    if (!objects.some((object) => object.type === type && object.name === name)) {
      objects.push({ ...scope, name, type, source: 'CURRENT_TABLE' });
    }
  }
  return { timeZone, selection: scope, objects };
};

export const agentContextSummary = (context: unknown): string | undefined => {
  if (!context || typeof context !== 'object') return undefined;
  const { selection, objects } = context as Record<string, unknown>;
  const targets = Array.isArray(objects) && objects.length ? objects : selection ? [selection] : [];
  const labels = targets.flatMap((target: unknown) => {
    if (!target || typeof target !== 'object') return [];
    const item = target as Record<string, unknown>;
    const name = typeof item.dataSourceName === 'string' ? item.dataSourceName : item.dataSourceId;
    return [[name, item.database, item.schema, item.name].filter((part): part is string =>
      typeof part === 'string' && part.length > 0).join(' / ')];
  });
  return [...new Set(labels)].join(' · ') || undefined;
};

export const agentContextDatabaseType = (context: unknown): string | undefined => {
  if (!context || typeof context !== 'object') return undefined;
  const value = context as Record<string, unknown>;
  const selection = value.selection;
  if (selection && typeof selection === 'object') {
    const databaseType = (selection as Record<string, unknown>).databaseType;
    if (typeof databaseType === 'string' && databaseType) return databaseType;
  }
  const objects = value.objects;
  if (Array.isArray(objects)) {
    const object = objects.find((item) => item && typeof item === 'object');
    const databaseType = object && (object as Record<string, unknown>).databaseType;
    if (typeof databaseType === 'string' && databaseType) return databaseType;
  }
  return undefined;
};
