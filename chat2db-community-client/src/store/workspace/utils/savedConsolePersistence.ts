import type { IConsole, ICreateConsole } from '@/typings';

interface SavedConsolePersistenceService {
  getSavedConsole: (params: { id: number }) => Promise<IConsole | null>;
  createConsole: (params: ICreateConsole) => Promise<number>;
  updateSavedConsole: (params: Partial<IConsole> & { id: number }) => Promise<unknown>;
}

interface PersistSavedConsoleParams {
  manual: boolean;
  createParams: ICreateConsole & { id: number };
  updateParams: Partial<IConsole> & { id: number };
}

export interface PersistSavedConsoleResult {
  action: 'created' | 'updated';
  consoleId: number;
}

function isSavedConsoleNotFoundError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'errorCode' in error &&
    (error as { errorCode?: unknown }).errorCode === 'common.dataNotFound'
  );
}

export async function persistSavedConsoleRecord(
  service: SavedConsolePersistenceService,
  params: PersistSavedConsoleParams,
): Promise<PersistSavedConsoleResult> {
  if (params.manual) {
    let savedConsole: IConsole | null;
    try {
      savedConsole = await service.getSavedConsole({ id: params.updateParams.id });
    } catch (error) {
      if (!isSavedConsoleNotFoundError(error)) {
        throw error;
      }
      savedConsole = null;
    }
    if (!savedConsole) {
      const consoleId = await service.createConsole(params.createParams);
      return { action: 'created', consoleId };
    }
  }

  await service.updateSavedConsole(params.updateParams);
  return { action: 'updated', consoleId: params.updateParams.id };
}
