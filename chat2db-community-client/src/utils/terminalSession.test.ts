import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const requireForMocks = createRequire(__filename);

async function run() {
  let terminalDecision: 'confirm' | 'cancel' = 'confirm';
  let transactionDecision = true;
  const calls: string[] = [];
  const nodeModule = requireForMocks('node:module') as any;
  const originalLoad = nodeModule.Module._load;
  nodeModule.Module._load = function mockedLoad(request: string, ...args: any[]) {
    if (request === '@chat2db/ui') {
      return {
        staticModal: {
          confirm: (options: { onOk: () => void; onCancel: () => void }) => {
            calls.push('terminal-confirmation');
            queueMicrotask(() => {
              if (terminalDecision === 'confirm') {
                options.onOk();
              } else {
                options.onCancel();
              }
            });
          },
        },
      };
    }
    if (request === '@/constants') {
      return { WorkspaceTabType: { Terminal: 'Terminal' } };
    }
    if (request === '@/constants/terminal') {
      return { isTerminalCloseConfirmationEnabled: () => true };
    }
    if (request === '@/i18n') {
      return { __esModule: true, default: (key: string) => key };
    }
    if (request === '@/jcef') {
      return {
        __esModule: true,
        default: {
          getTerminalStatuses: async () => ({ session: { alive: true, busy: true } }),
          killTerminals: async () => {
            calls.push('terminal-kill');
          },
        },
      };
    }
    if (request === '@/store/global') {
      return { useGlobalStore: { getState: () => ({ terminalSettings: {} }) } };
    }
    if (request === '@/utils/terminalBuffer') {
      return { clearPersistentTerminalBuffer: () => calls.push('terminal-buffer-clear') };
    }
    return originalLoad.apply(this, [request, ...args]);
  };
  Object.assign(globalThis, {
    window: {
      setTimeout,
      clearTimeout,
    },
  });
  const { confirmAndKillTerminalTabs } = await import('./terminalSession');
  nodeModule.Module._load = originalLoad;

  const tabs = [{ id: 1, type: 'Terminal', uniqueData: { terminalSessionId: 'session' } }] as any;
  const finalize = async () => {
    calls.push('transaction-finalize');
    return transactionDecision;
  };

  terminalDecision = 'cancel';
  assert.equal(await confirmAndKillTerminalTabs(tabs, tabs, finalize), false);
  assert.deepEqual(calls, ['terminal-confirmation']);

  calls.length = 0;
  terminalDecision = 'confirm';
  transactionDecision = false;
  assert.equal(await confirmAndKillTerminalTabs(tabs, tabs, finalize), false);
  assert.deepEqual(calls, ['terminal-confirmation', 'transaction-finalize']);

  calls.length = 0;
  transactionDecision = true;
  assert.equal(await confirmAndKillTerminalTabs(tabs, tabs, finalize), true);
  assert.deepEqual(calls, [
    'terminal-confirmation',
    'transaction-finalize',
    'terminal-kill',
    'terminal-buffer-clear',
  ]);

  console.log('Terminal close transaction ordering tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
