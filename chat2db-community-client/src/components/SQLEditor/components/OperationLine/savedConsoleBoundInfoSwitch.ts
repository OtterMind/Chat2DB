import type { IBoundInfo } from '@/typings/workspace';

export interface SavedConsoleBoundInfoSwitch {
  nextDBInfo: IBoundInfo;
  persistBeforeUiSwitch: boolean;
}

export function resolveSavedConsoleBoundInfoSwitch(current: IBoundInfo, requested: IBoundInfo) {
  const next = {
    ...requested,
    nameCustomized: requested.nameCustomized ?? current.nameCustomized ?? false,
  };
  const connectionTargetChanged =
    current.dataSourceId !== requested.dataSourceId ||
    current.databaseName !== requested.databaseName || current.schemaName !== requested.schemaName;
  const persistedConsole = typeof next.consoleId === 'number';

  return {
    nextDBInfo: next,
    persistBeforeUiSwitch: connectionTargetChanged && persistedConsole,
  };
}

export async function applySavedConsoleBoundInfoSwitch(
  resolution: SavedConsoleBoundInfoSwitch,
  persistSavedConsole: () => Promise<unknown>,
  setDBInfo: (next: IBoundInfo) => void,
) {
  if (resolution.persistBeforeUiSwitch) {
    await persistSavedConsole();
  }
  setDBInfo(resolution.nextDBInfo);
}
