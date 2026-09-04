import assert from 'node:assert/strict';
import { normalizeRunSqlFormValues } from './options';

const singleTransaction = normalizeRunSqlFormValues({
  commitMode: 'SINGLE_TRANSACTION',
  errorPolicy: 'CONTINUE',
  batchSize: 1000,
});

assert.equal(singleTransaction.errorPolicy, 'STOP');
assert.equal(singleTransaction.batchSize, 1000);

const batch = normalizeRunSqlFormValues({
  commitMode: 'BATCH',
  errorPolicy: 'CONTINUE',
});

assert.equal(batch.errorPolicy, 'CONTINUE');
