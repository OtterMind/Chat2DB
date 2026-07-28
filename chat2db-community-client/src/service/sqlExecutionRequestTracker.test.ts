import assert from 'node:assert/strict';
import {
  beginSqlExecutionRequest,
  canBeginSqlExecutionRequest,
  createSqlExecutionRequestTracker,
  finalizeSqlExecutionRequest,
  finishSqlExecutionRequest,
  getActiveSqlExecutionId,
  isSqlExecutionCancellationRequested,
  requestSqlExecutionCancellation,
  setSqlExecutionRequestId,
  SQL_EXECUTION_BUSY_ERROR_CODE,
  SqlExecutionBusyError,
} from './sqlExecutionRequestTracker';

const tracker = createSqlExecutionRequestTracker();
const firstRequestSequence = beginSqlExecutionRequest(tracker);
assert.equal(firstRequestSequence, 1);
assert.equal(canBeginSqlExecutionRequest(tracker), false);

assert.equal(beginSqlExecutionRequest(tracker), undefined, 'a duplicate start is rejected while the first start is pending');
assert.equal(
  setSqlExecutionRequestId(tracker, firstRequestSequence!, 'execution-1'),
  true,
  'the accepted request can still attach its execution ID after a duplicate start attempt',
);
assert.equal(
  getActiveSqlExecutionId(tracker),
  'execution-1',
  'a duplicate start does not clear the running execution cancellation target',
);

assert.equal(beginSqlExecutionRequest(tracker), undefined, 'a duplicate start is rejected while execution is running');
assert.equal(getActiveSqlExecutionId(tracker), 'execution-1');
const busyError = new SqlExecutionBusyError();
assert.equal(busyError.name, 'SqlExecutionBusyError');
assert.equal(busyError.code, SQL_EXECUTION_BUSY_ERROR_CODE);
assert.equal(finishSqlExecutionRequest(tracker, firstRequestSequence!), true);
assert.equal(canBeginSqlExecutionRequest(tracker), true);

const secondRequestSequence = beginSqlExecutionRequest(tracker);
assert.equal(secondRequestSequence, 2, 'a new request can start after the prior execution reaches a terminal state');

const pendingCancellationTracker = createSqlExecutionRequestTracker();
const pendingRequestSequence = beginSqlExecutionRequest(pendingCancellationTracker)!;
assert.equal(
  requestSqlExecutionCancellation(pendingCancellationTracker),
  undefined,
  'cancelling before the start response records intent even though no execution ID exists yet',
);
assert.equal(isSqlExecutionCancellationRequested(pendingCancellationTracker, pendingRequestSequence), true);
assert.equal(
  setSqlExecutionRequestId(pendingCancellationTracker, pendingRequestSequence, 'execution-pending-cancel'),
  true,
);
assert.equal(
  getActiveSqlExecutionId(pendingCancellationTracker),
  'execution-pending-cancel',
  'the late execution ID remains available for the deferred cancellation request',
);
assert.equal(finishSqlExecutionRequest(pendingCancellationTracker, pendingRequestSequence), true);
assert.equal(isSqlExecutionCancellationRequested(pendingCancellationTracker, pendingRequestSequence), false);

async function testSequentialExecutionFinalization() {
  const sequentialTracker = createSqlExecutionRequestTracker();
  const sequentialRequest = beginSqlExecutionRequest(sequentialTracker)!;
  let finalized = false;
  const firstResult = await finalizeSqlExecutionRequest(
    sequentialTracker,
    sequentialRequest,
    Promise.resolve('first'),
    () => {
      finalized = true;
    },
  );
  assert.equal(firstResult, 'first');
  assert.equal(finalized, true, 'the request finalizer runs before the exposed promise completes');
  assert.equal(
    canBeginSqlExecutionRequest(sequentialTracker),
    true,
    'an awaited web execution can start its next request in the same continuation',
  );

  const rejectedTracker = createSqlExecutionRequestTracker();
  const rejectedRequest = beginSqlExecutionRequest(rejectedTracker)!;
  const requestError = new Error('request failed');
  await assert.rejects(
    finalizeSqlExecutionRequest(rejectedTracker, rejectedRequest, Promise.reject(requestError)),
    requestError,
  );
  assert.equal(
    canBeginSqlExecutionRequest(rejectedTracker),
    true,
    'a rejected web execution also releases its request before the exposed promise settles',
  );
}

void testSequentialExecutionFinalization().then(() => {
  console.log('SQL execution request tracker tests passed');
});
