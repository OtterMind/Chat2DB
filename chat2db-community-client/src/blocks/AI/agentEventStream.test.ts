import assert from 'node:assert/strict';
import { mock } from 'node:test';
import { setImmediate } from 'node:timers/promises';
import type { AgentEvent } from '@/service/agent';
import { activeAgentRunId, followAgentRun, readAgentHistory } from './agentEventStream';
import { buildAgentTranscript, updateAgentApprovals } from './agentEvents';

const event = (sequence: number, type: AgentEvent['type'], runId = 'run'): AgentEvent => ({
  id: String(sequence), sessionId: 'session', runId, sequence, type, occurredAt: '',
  payload: type === 'RUN_FAILED' ? { error: 'model connection failed' } : { text: 'hello' },
});

async function main() {
  const controller = new AbortController();
  let release: (events: AgentEvent[]) => void = () => {};
  const pending = new Promise<AgentEvent[]>((resolve) => { release = resolve; });
  const received: AgentEvent[] = [];
  const stale = followAgentRun(() => pending, 'session', 'run', 0, controller.signal,
    (events) => received.push(...events));
  controller.abort();
  release([event(1, 'ASSISTANT_TEXT_DELTA'), event(2, 'RUN_COMPLETED')]);
  await stale;
  assert.deepEqual(received, [], 'late response must not write into a different conversation');

  const events = [event(1, 'RUN_COMPLETED', 'old'), event(2, 'ASSISTANT_TEXT_DELTA'),
    event(2, 'ASSISTANT_TEXT_DELTA'), event(3, 'RUN_FAILED')];
  const delivered: AgentEvent[] = [];
  const terminal = await followAgentRun(async () => events, 'session', 'run', 0,
    new AbortController().signal, (page) => delivered.push(...page));
  assert.equal(terminal?.type, 'RUN_FAILED');
  assert.deepEqual(delivered.map((item) => item.sequence), [2, 3]);
  const transcript = buildAgentTranscript(delivered);
  assert.equal(transcript[0].traceEntries[0].content, 'model connection failed');
  assert.equal(transcript[0].status, 'failed');

  const history = Array.from({ length: 1205 }, (_, index) => event(index + 1, 'ASSISTANT_TEXT_DELTA'));
  const loaded = await readAgentHistory(async ({ afterSequence, limit }) =>
    history.filter((item) => item.sequence > afterSequence).slice(0, limit), 'session',
  new AbortController().signal);
  assert.equal(loaded.length, 1205, 'history must load all pages');

  const completedHistory = [event(1, 'RUN_ACCEPTED'), ...history.map((item) => ({ ...item, sequence: item.sequence + 1 })),
    event(1207, 'RUN_COMPLETED'), event(1208, 'RUN_ACCEPTED', 'current'), event(1209, 'RUN_SUSPENDED', 'current')];
  const restored = await readAgentHistory(async ({ afterSequence, limit }) =>
    completedHistory.filter((item) => item.sequence > afterSequence).slice(0, limit), 'session', new AbortController().signal);
  assert.equal(activeAgentRunId(restored), 'current', 'restore the newest unfinished run after all history pages');
  assert.equal(activeAgentRunId([...restored, event(1210, 'RUN_OUTCOME_UNKNOWN', 'current')]), undefined,
    'a reconciled unknown outcome releases the run');

  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    let attempts = 0;
    const recoveredEvents: AgentEvent[] = [];
    const approval = { ...event(1, 'APPROVAL_REQUESTED'), payload: { approvalId: 'approval', command: 'select 1' } };
    const recovery = followAgentRun(async (query) => {
      attempts += 1;
      assert.equal(query.sessionId, 'session', 'a transport may mutate its URL parameters without corrupting retries');
      delete (query as Partial<typeof query>).sessionId;
      if (attempts === 1) return [approval];
      if (attempts === 2) throw new TypeError('Failed to fetch');
      assert.equal(query.afterSequence, 1, 'reconnection resumes from the last delivered event');
      return [approval, event(2, 'ASSISTANT_TEXT_DELTA'), event(3, 'RUN_COMPLETED')];
    }, 'session', 'run', 0, new AbortController().signal, (page) => recoveredEvents.push(...page));
    await setImmediate();
    mock.timers.tick(400);
    await setImmediate();
    assert.equal(updateAgentApprovals([], recoveredEvents)[0].status, 'pending',
      'a connection failure must not close an approval or create a run terminal event');
    assert.equal(attempts, 2);
    mock.timers.tick(1_000);
    assert.equal((await recovery)?.type, 'RUN_COMPLETED');
    assert.deepEqual(recoveredEvents.map((item) => item.sequence), [1, 2, 3]);

    let timeoutAttempts = 0;
    let latePage: (events: AgentEvent[]) => void = () => {};
    const timedOutEvents: AgentEvent[] = [];
    const timedOut = followAgentRun(() => {
      timeoutAttempts += 1;
      return timeoutAttempts === 1 ? new Promise((resolve) => { latePage = resolve; })
        : Promise.resolve([event(1, 'RUN_COMPLETED')]);
    }, 'session', 'run', 0, new AbortController().signal, (page) => timedOutEvents.push(...page));
    mock.timers.tick(15_000);
    await setImmediate();
    mock.timers.tick(1_000);
    assert.equal((await timedOut)?.type, 'RUN_COMPLETED', 'a hung request recovers after its deadline');
    latePage([event(2, 'ASSISTANT_TEXT_DELTA')]);
    await setImmediate();
    assert.deepEqual(timedOutEvents.map((item) => item.sequence), [1], 'discard late data from the timed-out request');

    const cancelled = new AbortController();
    let cancelledAttempts = 0;
    const reconnecting = followAgentRun(async () => {
      cancelledAttempts += 1;
      throw new TypeError('offline');
    }, 'session', 'run', 0, cancelled.signal, () => assert.fail('cancelled observer must not deliver events'));
    await setImmediate();
    cancelled.abort();
    await reconnecting;
    mock.timers.tick(60_000);
    assert.equal(cancelledAttempts, 1, 'leaving a session cancels pending retries');

    let permanentAttempts = 0;
    const permanent = followAgentRun(async () => {
      permanentAttempts += 1;
      throw Object.assign(new Error('business failure'), { errorCode: 'pi.invalidResponse' });
    }, 'session', 'run', 0, new AbortController().signal, () => assert.fail('a failed observer must not deliver events'));
    await assert.rejects(permanent, /business failure/, 'a business error must reach the caller without retrying');
    assert.equal(permanentAttempts, 1);

    let boundedAttempts = 0;
    const bounded = followAgentRun(async () => {
      boundedAttempts += 1;
      throw new TypeError('offline');
    }, 'session', 'run', 0, new AbortController().signal, () => assert.fail('a failed observer must not deliver events'));
    const boundedFailure = bounded.then(() => undefined, (error: unknown) => error);
    for (let tick = 0; tick < 12; tick += 1) {
      mock.timers.tick(10_000);
      await setImmediate();
    }
    assert.match(String(await boundedFailure), /offline/, 'reconnects must stop once the attempt budget is spent');
    assert.equal(boundedAttempts, 9, 'one initial read plus the bounded reconnects');
  } finally {
    mock.timers.reset();
  }
}

void main().catch((error) => { console.error(error); process.exitCode = 1; });
