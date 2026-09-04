import type { ITransactionRequest, ITransactionStateResponse } from '@/service/transaction';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';
import { TransactionMode, TransactionOutcome } from '@/constants/transaction';
import { reconcileTransactionState } from './transactionExecution';

export interface TxConsole {
  consoleId: number;
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
}

export type CloseAction = 'commit' | 'rollback';

interface TransactionSessionStore {
  getTransactionState?: (consoleId: number) => TransactionState | undefined;
  setTransactionState: (consoleId: number, patch: Partial<TransactionState>) => void;
}

export interface ReleaseTransactionDependencies {
  store: TransactionSessionStore;
  commitTransaction: (request: ITransactionRequest) => Promise<ITransactionStateResponse>;
  releaseTransaction: (request: ITransactionRequest) => Promise<ITransactionStateResponse>;
  getTransactionState?: (request: ITransactionRequest) => Promise<ITransactionStateResponse>;
  onUnknownOutcome?: () => void;
}

export async function releaseTransactionConsoles(
  consoles: TxConsole[],
  action: CloseAction,
  dependencies: ReleaseTransactionDependencies,
): Promise<boolean> {
  const results = await Promise.all(
    consoles.map(async (console) => {
      const request = {
        dataSourceId: console.dataSourceId,
        databaseName: console.databaseName,
        schemaName: console.schemaName,
        consoleId: console.consoleId,
      };
      try {
        const result =
            action === 'commit'
              ? await dependencies.commitTransaction(request)
              : await dependencies.releaseTransaction(request);
        if (result.outcome === TransactionOutcome.UNKNOWN) {
          dependencies.onUnknownOutcome?.();
          dependencies.store.setTransactionState(console.consoleId, {
            inTransaction: true,
            lastOutcome: result.outcome,
            lastError: result.lastError,
          });
          await reconcileUnknownTransactionState(console, request, dependencies);
          return false;
        }
        dependencies.store.setTransactionState(console.consoleId, transactionStatePatch(result));
        return true;
      } catch (error) {
        dependencies.store.setTransactionState(console.consoleId, {
          inTransaction: true,
          lastOutcome: TransactionOutcome.UNKNOWN,
          lastError: String(error),
        });
        await reconcileUnknownTransactionState(console, request, dependencies);
        return false;
      }
    }),
  );
  return results.every(Boolean);
}

async function reconcileUnknownTransactionState(
  console: TxConsole,
  request: ITransactionRequest,
  dependencies: ReleaseTransactionDependencies,
) {
  if (!dependencies.getTransactionState) {
    return;
  }
  try {
    const current = dependencies.store.getTransactionState?.(console.consoleId);
    const result = await dependencies.getTransactionState(request);
    dependencies.store.setTransactionState(console.consoleId, reconcileTransactionState(current, result));
  } catch (_error) {
    // Keep the recovery controls visible when reconciliation also cannot prove the outcome.
  }
}

function transactionStatePatch(result: ITransactionStateResponse | undefined): Partial<TransactionState> {
  return {
    mode: result?.mode === TransactionMode.MANUAL ? TransactionMode.MANUAL : TransactionMode.AUTO,
    inTransaction: Boolean(result?.inTransaction),
    lastOutcome: result?.outcome,
    lastError: result?.lastError,
  };
}
