import { ConsoleOpenedStatus } from '@/constants/console';
import { ConsoleStatus, DatabaseTypeCode } from '@/constants/common';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IConsole, ICreateConsole, IWorkspaceTab } from '@/typings';
import { executeSavedConsoleRemoval, resolveSavedConsoleRemoval } from './savedConsoleLifecycle';
import { SavedConsoleMutationCoordinator } from './savedConsoleMutationCoordinator';
import { persistSavedConsoleRecord } from './savedConsolePersistence';

function assertRemovalPlans() {
  const openConsoleTab = {
    id: 'workspace-tab-42',
    type: WorkspaceTabType.CONSOLE,
    uniqueData: {
      consoleId: 42,
    },
  } as IWorkspaceTab;

  const keepDraftPlan = resolveSavedConsoleRemoval([openConsoleTab], 42);
  if (keepDraftPlan.action !== 'keepDraft' || keepDraftPlan.workspaceTabId !== 'workspace-tab-42') {
    throw new Error(`expected an open saved console to remain as a draft: ${JSON.stringify(keepDraftPlan)}`);
  }

  const unrelatedTab = {
    id: 42,
    type: WorkspaceTabType.VIEW,
    uniqueData: {},
  } as IWorkspaceTab;
  const deletePlan = resolveSavedConsoleRemoval([unrelatedTab], 42);
  if (deletePlan.action !== 'delete') {
    throw new Error(`expected a closed saved console to be deleted: ${JSON.stringify(deletePlan)}`);
  }
}

function createConsoleParams(): ICreateConsole & { id: number } {
  return {
    id: 42,
    name: 'Recovered query',
    ddl: 'select 42',
    dataSourceId: 1,
    dataSourceName: 'Local MySQL',
    type: DatabaseTypeCode.MYSQL,
    databaseName: 'chat2db',
    status: ConsoleStatus.RELEASE,
    tabOpened: ConsoleOpenedStatus.IS_OPEN,
    operationType: WorkspaceTabType.CONSOLE,
  };
}

async function testMissingRecordRecreatesSameId() {
  const calls: string[] = [];
  let created: ICreateConsole | undefined;
  const result = await persistSavedConsoleRecord(
    {
      getSavedConsole: async ({ id }) => {
        calls.push(`get:${id}`);
        return null;
      },
      createConsole: async (params) => {
        calls.push(`create:${params.id}`);
        created = params;
        return params.id!;
      },
      updateSavedConsole: async () => {
        throw new Error('missing saved console must not use update');
      },
    },
    {
      manual: true,
      createParams: createConsoleParams(),
      updateParams: { id: 42, status: ConsoleStatus.RELEASE, ddl: 'select 42' },
    },
  );

  if (result !== 'created' || calls.join(',') !== 'get:42,create:42') {
    throw new Error(`unexpected missing-record recovery flow: ${result} / ${calls.join(',')}`);
  }
  if (created?.id !== 42 || created.name !== 'Recovered query' || created.ddl !== 'select 42') {
    throw new Error(`missing-record recovery changed identity or content: ${JSON.stringify(created)}`);
  }
}

async function testExistingRecordUpdatesWithoutCreate() {
  const calls: string[] = [];
  const existing = createConsoleParams() as IConsole;
  const result = await persistSavedConsoleRecord(
    {
      getSavedConsole: async () => existing,
      createConsole: async () => {
        throw new Error('existing saved console must not use create');
      },
      updateSavedConsole: async ({ id }) => {
        calls.push(`update:${id}`);
      },
    },
    {
      manual: true,
      createParams: createConsoleParams(),
      updateParams: { id: 42, status: ConsoleStatus.RELEASE, ddl: 'select 43' },
    },
  );

  if (result !== 'updated' || calls.join(',') !== 'update:42') {
    throw new Error(`unexpected existing-record update flow: ${result} / ${calls.join(',')}`);
  }
}

async function testOpenAndClosedRemovalExecution() {
  const coordinator = new SavedConsoleMutationCoordinator();
  const calls: string[] = [];
  await executeSavedConsoleRemoval(
    42,
    { action: 'keepDraft', workspaceTabId: 'workspace-tab-42' },
    {
      updateSavedConsole: async ({ id, status }) => {
        calls.push(`update:${id}:${status}`);
      },
      deleteSavedConsole: async () => {
        throw new Error('open saved console must not be physically deleted');
      },
      updateWorkspaceTabBoundInfo: ({ workspaceTabId, status }) => {
        calls.push(`tab:${workspaceTabId}:${status}`);
      },
    },
    coordinator,
  );
  await executeSavedConsoleRemoval(
    43,
    { action: 'delete' },
    {
      updateSavedConsole: async () => {
        throw new Error('closed saved console must not be demoted');
      },
      deleteSavedConsole: async ({ id }) => {
        calls.push(`delete:${id}`);
      },
      updateWorkspaceTabBoundInfo: () => {
        throw new Error('closed saved console must not update a workspace tab');
      },
    },
    coordinator,
  );

  const expected = `update:42:${ConsoleStatus.DRAFT},tab:workspace-tab-42:${ConsoleStatus.DRAFT},delete:43`;
  if (calls.join(',') !== expected) {
    throw new Error(`unexpected saved-console removal calls: ${calls.join(',')}`);
  }
}

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function testRemovalWinsOverInflightSave() {
  const coordinator = new SavedConsoleMutationCoordinator();
  const saveGate = deferred();
  const removalGate = deferred();
  const calls: string[] = [];

  const save = coordinator.save(42, 'automatic', async () => {
    calls.push('save:start');
    await saveGate.promise;
    calls.push('save:end');
  });
  const removal = coordinator.remove(42, true, async () => {
    calls.push('remove:start');
    await removalGate.promise;
    calls.push('remove:end');
  });
  const suppressedAutoSave = await coordinator.save(42, 'automatic', async () => {
    calls.push('unexpected:auto-save');
  });

  if (suppressedAutoSave.executed) {
    throw new Error('automatic save was not blocked after removal started');
  }
  await Promise.resolve();
  if (calls.join(',') !== 'save:start') {
    throw new Error(`removal was not serialized behind the in-flight save: ${calls.join(',')}`);
  }

  saveGate.resolve();
  const saveResult = await save;
  await Promise.resolve();
  if (saveResult.current || calls.join(',') !== 'save:start,save:end,remove:start') {
    throw new Error(`stale save completion was accepted: ${JSON.stringify({ saveResult, calls })}`);
  }

  removalGate.resolve();
  const removalResult = await removal;
  if (!removalResult.current || calls.join(',') !== 'save:start,save:end,remove:start,remove:end') {
    throw new Error(`removal did not win the mutation race: ${JSON.stringify({ removalResult, calls })}`);
  }
}

async function testMutationQueueContinuesAfterFailure() {
  const coordinator = new SavedConsoleMutationCoordinator();
  const calls: string[] = [];
  const failedSave = coordinator.save(42, 'automatic', async () => {
    calls.push('failed');
    throw new Error('expected failure');
  });
  const nextSave = coordinator.save(42, 'manual', async () => {
    calls.push('next');
  });

  await failedSave.catch(() => undefined);
  const nextResult = await nextSave;
  if (!nextResult.current || calls.join(',') !== 'failed,next') {
    throw new Error(`failed mutation stalled the queue: ${JSON.stringify({ nextResult, calls })}`);
  }
}

async function testFailedRemovalRestoresAutomaticSaves() {
  const coordinator = new SavedConsoleMutationCoordinator();
  await coordinator
    .remove(42, true, async () => {
      throw new Error('expected removal failure');
    })
    .catch(() => undefined);

  let automaticSaveRan = false;
  const automaticSave = await coordinator.save(42, 'automatic', async () => {
    automaticSaveRan = true;
  });
  if (!automaticSave.executed || !automaticSave.current || !automaticSaveRan) {
    throw new Error(`failed removal left automatic saves blocked: ${JSON.stringify(automaticSave)}`);
  }
}

async function testFailedManualSaveKeepsDraftBlock() {
  const coordinator = new SavedConsoleMutationCoordinator();
  await coordinator.remove(42, true, async () => undefined);
  await coordinator
    .save(42, 'manual', async () => {
      throw new Error('expected manual save failure');
    })
    .catch(() => undefined);

  let automaticSaveRan = false;
  const automaticSave = await coordinator.save(42, 'automatic', async () => {
    automaticSaveRan = true;
  });
  if (automaticSave.executed || automaticSaveRan) {
    throw new Error('failed manual save removed the draft automatic-save block');
  }
}

async function run() {
  assertRemovalPlans();
  await testMissingRecordRecreatesSameId();
  await testExistingRecordUpdatesWithoutCreate();
  await testOpenAndClosedRemovalExecution();
  await testRemovalWinsOverInflightSave();
  await testMutationQueueContinuesAfterFailure();
  await testFailedRemovalRestoresAutomaticSaves();
  await testFailedManualSaveKeepsDraftBlock();
  console.log('Saved console lifecycle tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
