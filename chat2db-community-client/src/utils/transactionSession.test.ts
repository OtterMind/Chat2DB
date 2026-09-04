import assert from 'node:assert/strict';
import { releaseTransactionConsoles } from './transactionSessionCore';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';
import {
  TransactionIsolationLevel,
  TransactionMode,
  TransactionOutcome,
} from '@/constants/transaction';

async function run() {
{
  const patches: Partial<TransactionState>[] = [];
  let unknownOutcomes = 0;
  const success = await releaseTransactionConsoles(
    [{ consoleId: 42, dataSourceId: 7, databaseName: 'shop', schemaName: 'public' }],
    'rollback',
    {
      store: {
        setTransactionState: (_consoleId, patch) => patches.push(patch),
      },
      releaseTransaction: async (request) => {
        assert.equal(request.consoleId, 42);
        assert.equal(request.dataSourceId, 7);
        return {
          inTransaction: false,
          mode: TransactionMode.AUTO,
          isolationLevel: TransactionIsolationLevel.DEFAULT,
          supportedIsolationLevels: [],
          outcome: TransactionOutcome.UNKNOWN,
          lastError: 'rollback outcome unknown',
        };
      },
      commitTransaction: async () => {
        throw new Error('commit must not be called for rollback close');
      },
      onUnknownOutcome: () => {
        unknownOutcomes += 1;
      },
    },
  );

  assert.equal(success, false);
  assert.equal(unknownOutcomes, 1);
  assert.deepEqual(patches, [
    {
      inTransaction: true,
      lastOutcome: TransactionOutcome.UNKNOWN,
      lastError: 'rollback outcome unknown',
    },
  ]);
}

{
  const patches: Partial<TransactionState>[] = [];
  const success = await releaseTransactionConsoles(
    [{ consoleId: 44, dataSourceId: 8 }],
    'commit',
    {
      store: {
        setTransactionState: (_consoleId, patch) => patches.push(patch),
      },
      commitTransaction: async () => {
        throw new Error('network lost');
      },
      releaseTransaction: async () => {
        throw new Error('release must not be called for commit close');
      },
    },
  );

  assert.equal(success, false);
  assert.equal(patches[0]?.inTransaction, true, 'transport failure must keep transaction recovery controls available');
  assert.equal(patches[0]?.lastOutcome, TransactionOutcome.UNKNOWN);
}

{
  const patches: Partial<TransactionState>[] = [];
  const success = await releaseTransactionConsoles(
    [{ consoleId: 43, dataSourceId: 8 }],
    'commit',
    {
      store: {
        setTransactionState: (_consoleId, patch) => patches.push(patch),
      },
      commitTransaction: async () => ({
        inTransaction: false,
        mode: TransactionMode.AUTO,
        isolationLevel: TransactionIsolationLevel.DEFAULT,
        supportedIsolationLevels: [],
        outcome: TransactionOutcome.COMMITTED,
      }),
      releaseTransaction: async () => {
        throw new Error('release must not be called for commit close');
      },
    },
  );

  assert.equal(success, true);
  assert.equal(patches[0]?.lastOutcome, TransactionOutcome.COMMITTED);
}

  console.log('Transaction session tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
