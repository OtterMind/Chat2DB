import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const requireForMocks = createRequire(__filename);

async function run() {
  let dirtyEditorsConfirmed = false;
  const calls: string[] = [];
  const nodeModule = requireForMocks('node:module') as any;
  const originalLoad = nodeModule.Module._load;
  nodeModule.Module._load = function mockedLoad(request: string, ...args: any[]) {
    if (request === '@chat2db/ui') {
      return { staticModal: { confirm: () => undefined } };
    }
    if (request === 'antd') {
      return { Button: () => null };
    }
    if (request === '@/i18n') {
      return { __esModule: true, default: (key: string) => key };
    }
    if (request === '@/store/global') {
      return { useGlobalStore: { getState: () => ({ editorSettings: {} }) } };
    }
    if (request === '@/utils/terminalSession') {
      return {
        confirmAndKillTerminalTabs: async (
          _tabs: unknown,
          _allTabs: unknown,
          beforeFinalize?: () => Promise<boolean>,
        ) => {
          calls.push('terminal-confirmation');
          if (beforeFinalize && !(await beforeFinalize())) {
            return false;
          }
          calls.push('terminal-kill');
          return true;
        },
      };
    }
    if (request === './editorCloseGuard') {
      return {
        confirmDirtyEditorTabs: async () => {
          calls.push('dirty-confirmation');
          return dirtyEditorsConfirmed;
        },
        isEditorCloseConfirmationEnabled: () => true,
        prepareEditorsForApplicationExit: async () => true,
        waitForPendingEditorTabs: async () => true,
      };
    }
    return originalLoad.apply(this, [request, ...args]);
  };
  const { confirmWorkspaceTabsClose } = await import('./editorCloseConfirmation');
  nodeModule.Module._load = originalLoad;

  const finalize = async () => {
    calls.push('transaction-finalize');
    return true;
  };

  assert.equal(await confirmWorkspaceTabsClose([], [], {}, finalize), false);
  assert.deepEqual(calls, ['dirty-confirmation']);

  calls.length = 0;
  dirtyEditorsConfirmed = true;
  assert.equal(await confirmWorkspaceTabsClose([], [], {}, finalize), true);
  assert.deepEqual(calls, [
    'dirty-confirmation',
    'terminal-confirmation',
    'transaction-finalize',
    'terminal-kill',
  ]);

  console.log('Workspace close confirmation ordering tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
