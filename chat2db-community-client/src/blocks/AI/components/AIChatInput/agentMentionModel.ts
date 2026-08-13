import type { AgentDataScope } from '@/service/agent';

interface ContextScope {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
}

const sameName = (left?: string, right?: string) => !left || (!!right && left.toLowerCase() === right.toLowerCase());

export function resolveMentionTaskScopes(scopes: AgentDataScope[], context?: ContextScope | null): AgentDataScope[] {
  if (!context?.dataSourceId) {
    return scopes;
  }
  const matches = scopes.filter(
    (scope) =>
      scope.dataSourceId === context.dataSourceId &&
      sameName(scope.databaseName, context.databaseName) &&
      sameName(scope.schemaName, context.schemaName),
  );
  return matches.map((scope) => ({
    ...scope,
    databaseName: context.databaseName || scope.databaseName,
    schemaName: context.schemaName || scope.schemaName,
    tableNames: [...(scope.tableNames || [])],
    excludedTableNames: [...(scope.excludedTableNames || [])],
  }));
}

export function removeAgentMention(input: string, agentName?: string) {
  if (!agentName) {
    return input.trim();
  }
  const escapedName = agentName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return input.replace(new RegExp(`@${escapedName}(?:\\s+|$)`, 'i'), '').trim();
}
