import type { IBoundInfo } from '@/typings';

export function getSqlCompletionContextId(dbInfo: IBoundInfo | null | undefined): number | undefined {
  const contextId = dbInfo?.consoleId ?? dbInfo?.workspaceTabId;
  return typeof contextId === 'number' && Number.isFinite(contextId) ? contextId : undefined;
}
