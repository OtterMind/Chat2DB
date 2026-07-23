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

export async function persistSavedConsoleRecord(
  service: SavedConsolePersistenceService,
  params: PersistSavedConsoleParams,
): Promise<'created' | 'updated'> {
  if (params.manual) {
    const savedConsole = await service.getSavedConsole({ id: params.updateParams.id });
    if (!savedConsole) {
      await service.createConsole(params.createParams);
      return 'created';
    }
  }

  await service.updateSavedConsole(params.updateParams);
  return 'updated';
}
