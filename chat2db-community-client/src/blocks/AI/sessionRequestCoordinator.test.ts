import assert from 'node:assert/strict';
import { AiSessionRequestCoordinator } from './sessionRequestCoordinator';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

async function run() {
  const externalCoordinator = new AiSessionRequestCoordinator();
  const externalNewSessionOwner = externalCoordinator.beginNewSession();
  const externalSendWithOldSession = externalCoordinator.resolveSendContext(
    externalNewSessionOwner,
    'stale-session',
    [{ role: 'user', content: 'stale history' }],
  );
  assert.deepEqual(
    externalSendWithOldSession,
    { sessionId: undefined, history: [] },
    'an external new-session send must not inherit the render closure session',
  );
  const externalSendWithOldHistory = externalCoordinator.resolveSendContext(
    externalNewSessionOwner,
    null,
    [{ role: 'user', content: 'stale history' }],
  );
  assert.deepEqual(
    externalSendWithOldHistory,
    { sessionId: undefined, history: [] },
    'an external new-session send must not inherit the render closure history',
  );

  const coordinator = new AiSessionRequestCoordinator();
  const committedSessions: string[] = [];
  let loading = false;

  const loadSession = async (sessionId: string, response: Promise<string>) => {
    const owner = coordinator.beginSessionLoad(sessionId);
    loading = true;
    try {
      const resolvedSession = await response;
      if (coordinator.isCurrent(owner)) {
        committedSessions.push(resolvedSession);
      }
    } finally {
      if (coordinator.finishSessionLoad(owner)) {
        loading = false;
      }
    }
  };

  const first = deferred<string>();
  const second = deferred<string>();
  const firstLoad = loadSession('session-a', first.promise);
  const secondLoad = loadSession('session-b', second.promise);

  first.resolve('session-a');
  await firstLoad;
  assert.equal(loading, true, 'an obsolete finally must not clear the latest loading state');
  assert.deepEqual(committedSessions, [], 'an obsolete response must not commit while the latest request is pending');

  second.resolve('session-b');
  await secondLoad;
  assert.equal(loading, false);
  assert.deepEqual(committedSessions, ['session-b']);

  const reverseCoordinator = new AiSessionRequestCoordinator();
  const reverseCommits: string[] = [];
  const resolveReverseLoad = async (sessionId: string, response: Promise<string>) => {
    const owner = reverseCoordinator.beginSessionLoad(sessionId);
    const resolvedSession = await response;
    if (reverseCoordinator.isCurrent(owner)) {
      reverseCommits.push(resolvedSession);
    }
    reverseCoordinator.finishSessionLoad(owner);
  };
  const reverseFirst = deferred<string>();
  const reverseSecond = deferred<string>();
  const reverseFirstLoad = resolveReverseLoad('session-a', reverseFirst.promise);
  const reverseSecondLoad = resolveReverseLoad('session-b', reverseSecond.promise);
  reverseSecond.resolve('session-b');
  await reverseSecondLoad;
  reverseFirst.resolve('session-a');
  await reverseFirstLoad;
  assert.deepEqual(reverseCommits, ['session-b'], 'B must remain visible when A resolves after B');

  const pendingLoad = coordinator.beginSessionLoad('session-c');
  const newSessionOwner = coordinator.beginNewSession();
  assert.equal(coordinator.isCurrent(pendingLoad), false, 'new chat must invalidate an outstanding session load');
  assert.equal(coordinator.finishSessionLoad(pendingLoad), false);
  assert.equal(coordinator.isCurrent(newSessionOwner), true);

  const nextLoad = coordinator.beginSessionLoad('session-d');
  assert.equal(coordinator.resolveSendContext(newSessionOwner, 'stale-session', ['stale']), null);
  assert.equal(coordinator.isCurrent(nextLoad), true);

  console.log('AI session request coordinator tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
