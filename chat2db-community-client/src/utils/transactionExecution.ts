import type { ITransactionBeginRequest, ITransactionStateResponse } from '@/service/transaction';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';
import type { IExecuteSqlParams } from '@/typings';
import { TransactionIsolationLevel, TransactionMode, TransactionOutcome } from '@/constants/transaction';

export interface TransactionStateAccess {
  getTransactionState: (consoleId: number) => TransactionState | undefined;
  setTransactionState: (consoleId: number, patch: Partial<TransactionState>) => void;
}

export type BeginTransaction = (request: ITransactionBeginRequest) => Promise<ITransactionStateResponse>;

const pendingTransactionBegins = new Map<number, Promise<ITransactionStateResponse>>();

export async function waitForPendingTransactionBegins(consoleIds: number[]): Promise<void> {
  const pending = [...new Set(consoleIds)]
    .map((consoleId) => pendingTransactionBegins.get(consoleId))
    .filter((promise): promise is Promise<ITransactionStateResponse> => Boolean(promise));
  if (pending.length) {
    await Promise.allSettled(pending);
  }
}

export function reconcileTransactionState(
  current: TransactionState | undefined,
  result: ITransactionStateResponse,
): Partial<TransactionState> {
  const supportedIsolationLevels = result.supportedIsolationLevels ?? [];
  const inTransaction =
    result.outcome === TransactionOutcome.UNKNOWN
      ? current?.inTransaction ?? result.inTransaction
      : result.inTransaction;
  let isolationLevel = inTransaction
    ? result.isolationLevel ?? TransactionIsolationLevel.DEFAULT
    : current?.isolationLevel ?? result.isolationLevel ?? TransactionIsolationLevel.DEFAULT;
  if (
    supportedIsolationLevels.length > 0 &&
    !supportedIsolationLevels.includes(isolationLevel)
  ) {
    isolationLevel = TransactionIsolationLevel.DEFAULT;
  }
  return {
    mode: inTransaction ? TransactionMode.MANUAL : current?.mode ?? result.mode,
    inTransaction,
    opening: false,
    isolationLevel,
    supportedIsolationLevels,
    lastOutcome: result.outcome,
    lastError: result.lastError,
  };
}

export async function ensureManualTransactionStarted(
  params: IExecuteSqlParams,
  stateAccess: TransactionStateAccess,
  beginTransaction: BeginTransaction,
): Promise<ITransactionStateResponse | undefined> {
  const { consoleId, dataSourceId } = params;
  if (typeof consoleId !== 'number' || dataSourceId == null) {
    return;
  }
  const state = stateAccess.getTransactionState(consoleId);
  if (!state || state.mode !== TransactionMode.MANUAL) {
    return;
  }

  const pending = pendingTransactionBegins.get(consoleId);
  if (pending) {
    return pending;
  }

  stateAccess.setTransactionState(consoleId, { opening: true, lastError: undefined });
  const begin = Promise.resolve()
    .then(async () => {
      try {
        const result = await beginTransaction({
          dataSourceId,
          databaseName: params.databaseName,
          schemaName: params.schemaName,
          consoleId,
          isolationLevel: state.isolationLevel ?? TransactionIsolationLevel.DEFAULT,
        });
        if (!result?.inTransaction) {
          throw new Error(result?.lastError || 'Failed to start the manual transaction');
        }
        stateAccess.setTransactionState(consoleId, {
          mode: TransactionMode.MANUAL,
          inTransaction: true,
          opening: false,
          isolationLevel: result.isolationLevel ?? state.isolationLevel ?? TransactionIsolationLevel.DEFAULT,
          supportedIsolationLevels: result.supportedIsolationLevels ?? state.supportedIsolationLevels,
          lastError: result.lastError,
        });
        return result;
      } catch (error) {
        stateAccess.setTransactionState(consoleId, {
          ...(state.inTransaction ? { inTransaction: true } : {}),
          opening: false,
          lastOutcome: TransactionOutcome.UNKNOWN,
          lastError: error instanceof Error ? error.message : String(error),
        });
        throw error;
      }
    })
    .finally(() => {
      if (pendingTransactionBegins.get(consoleId) === begin) {
        pendingTransactionBegins.delete(consoleId);
      }
    });
  pendingTransactionBegins.set(consoleId, begin);
  return begin;
}
