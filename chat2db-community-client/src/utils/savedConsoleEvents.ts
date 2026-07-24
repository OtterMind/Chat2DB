import { SAVED_CONSOLE_UPDATED_EVENT, type SavedConsoleUpdatedEventDetail } from '@/constants/workspace';
import type { IConsole } from '@/typings';

type SavedConsoleUpdateScope = Partial<SavedConsoleUpdatedEventDetail>;

export function emitSavedConsoleUpdated(scope: SavedConsoleUpdateScope, target: EventTarget = window): boolean {
  if (scope.dataSourceId === undefined || scope.databaseType === undefined) {
    return false;
  }

  const detail: SavedConsoleUpdatedEventDetail = {
    dataSourceId: scope.dataSourceId,
    databaseType: scope.databaseType,
    databaseName: scope.databaseName,
    schemaName: scope.schemaName,
  };
  target.dispatchEvent(new CustomEvent(SAVED_CONSOLE_UPDATED_EVENT, { detail }));
  return true;
}

type SavedConsoleRecordScope = Pick<IConsole, 'dataSourceId' | 'type' | 'databaseName' | 'schemaName'>;

export function emitSavedConsoleRecordUpdated(record: SavedConsoleRecordScope, target: EventTarget = window): boolean {
  return emitSavedConsoleUpdated(
    {
      dataSourceId: record.dataSourceId,
      databaseType: record.type,
      databaseName: record.databaseName,
      schemaName: record.schemaName,
    },
    target,
  );
}
