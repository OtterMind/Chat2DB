import createRequest from './base';
import type { IDataSourceExecutionContext } from './dmlRequest';

/** Request body for console-scoped transaction endpoints (mirrors backend ConsoleCloseRequest). */
export interface ITransactionRequest extends IDataSourceExecutionContext {
  consoleId?: number;
}

/** Transaction state returned by the backend. */
export interface ITransactionStateResponse {
  inTransaction: boolean;
  mode: string;
  outcome?: string;
  lastError?: string;
}

/** Begin a manual transaction for the console (borrow one connection, auto-commit off). */
const beginTransaction = createRequest<ITransactionRequest, ITransactionStateResponse>(
  '/api/rdb/transaction/begin',
  { method: 'post', errorLevel: false },
);

/** Commit the console's open transaction and release the bound connection. */
const commitTransaction = createRequest<ITransactionRequest, ITransactionStateResponse>(
  '/api/rdb/transaction/commit',
  { method: 'post', errorLevel: false },
);

/** Roll back the console's open transaction and release the bound connection. */
const rollbackTransaction = createRequest<ITransactionRequest, ITransactionStateResponse>(
  '/api/rdb/transaction/rollback',
  { method: 'post', errorLevel: false },
);

/** Query the console's current transaction state. */
const getTransactionState = createRequest<ITransactionRequest, ITransactionStateResponse>(
  '/api/rdb/transaction/state',
  { method: 'post', errorLevel: false },
);

/** Release the console's bound connection (rolls back any open transaction first). */
const releaseTransaction = createRequest<ITransactionRequest, void>('/api/rdb/transaction/release', {
  method: 'post',
  errorLevel: false,
});

export default {
  beginTransaction,
  commitTransaction,
  rollbackTransaction,
  getTransactionState,
  releaseTransaction,
};
