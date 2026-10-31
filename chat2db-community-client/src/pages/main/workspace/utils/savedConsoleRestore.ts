import { ConsoleStatus } from '@/constants/common';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IBoundInfo, IConsole } from '@/typings';

export type SavedConsoleReader = (params: { id: number }) => Promise<Pick<IConsole, 'ddl'> | null>;

interface CreateSavedConsoleLoadSQLParams {
  tabId: number | string;
  tabType?: WorkspaceTabType | null;
  uniqueData?: IBoundInfo;
  readSavedConsole: SavedConsoleReader;
}

export function createSavedConsoleLoadSQL(
  params: CreateSavedConsoleLoadSQLParams,
): (() => Promise<string>) | undefined {
  const { tabId, tabType, uniqueData, readSavedConsole } = params;
  if (tabType !== WorkspaceTabType.CONSOLE || uniqueData?.status !== ConsoleStatus.RELEASE) {
    return undefined;
  }
  const consoleId = uniqueData.consoleId ?? tabId;
  if (typeof consoleId !== 'number') {
    return undefined;
  }
  return () =>
    readSavedConsole({ id: consoleId })
      .then((savedConsole) => savedConsole?.ddl ?? uniqueData.ddl ?? '')
      .catch(() => uniqueData.ddl || '');
}
