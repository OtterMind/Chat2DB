import assert from 'node:assert/strict';
import { mock } from 'node:test';
import { setImmediate } from 'node:timers/promises';
import type { AgentEvent } from '@/service/agent';
import { activeAgentRunId, followAgentRun, readAgentHistoryBefore, readAgentHistoryTail,
  type ReadAgentEvents } from './agentEventStream';
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

  /** Reads pages backwards, the way the server pages event files by sequence. */
  const countingRead = (source: AgentEvent[]) => {
    const reads: number[] = [];
    const read: ReadAgentEvents = async ({ beforeSequence = 0, limit }) => {
      reads.push(beforeSequence);
      return source.filter((item) => item.sequence < beforeSequence).slice(-limit);
    };
    return { reads, read };
  };

  // A long conversation opens on its newest events: one tail read, extended at most once to a turn start.
  const history = Array.from({ length: 1205 }, (_, index) => event(index + 1, 'ASSISTANT_TEXT_DELTA'));
  const tail = countingRead(history);
  const loaded = await readAgentHistoryTail(tail.read, 'session', new AbortController().signal, 1205);
  assert.deepEqual(tail.reads, [1206, 1006], 'the tail is read from the end, never from the start of the history');
  assert.equal(loaded.length, 400, 'alignment stops after one extra page even without a turn boundary');
  assert.equal(loaded[loaded.length - 1].sequence, 1205, 'the newest event stays in the window');

  const completedHistory = [
    ...Array.from({ length: 300 }, (_, index) => event(index + 1, 'ASSISTANT_TEXT_DELTA')),
    event(301, 'RUN_COMPLETED', 'old'), event(302, 'RUN_ACCEPTED', 'current'),
    event(303, 'RUN_SUSPENDED', 'current'),
  ];
  const restored = await readAgentHistoryTail(countingRead(completedHistory).read, 'session',
    new AbortController().signal, 303);
  assert.equal(restored[0].sequence, 302, 'a window cut inside a turn shrinks back to the turn start');
  assert.equal(restored[0].type, 'RUN_ACCEPTED');
  assert.equal(activeAgentRunId(restored), 'current', 'restore the newest unfinished run from the tail window');
  assert.equal(activeAgentRunId([...restored, event(304, 'RUN_OUTCOME_UNKNOWN', 'current')]), undefined,
    'a reconciled unknown outcome releases the run');

  // Reading further back costs exactly one page per request and never doubles an event.
  const pagedRead = countingRead(completedHistory);
  const earlier = await readAgentHistoryBefore(pagedRead.read, 'session', new AbortController().signal, 302);
  assert.deepEqual(pagedRead.reads, [302, 102], 'one page back, plus at most one alignment read');
  assert.deepEqual(earlier.map((item) => item.sequence),
    Array.from({ length: 301 }, (_, index) => index + 1),
    'the page is contiguous, keeps every earlier event, and stops before the loaded window');

  const openingRead = countingRead(history.slice(0, 120));
  const opening = await readAgentHistoryTail(openingRead.read, 'session', new AbortController().signal, 120);
  assert.deepEqual(openingRead.reads, [121], 'a short history needs no alignment read');
  assert.equal(opening.length, 120, 'a window that reaches sequence 1 keeps the turn it has');

  let startReads = 0;
  const atStart = await readAgentHistoryBefore(async () => { startReads += 1; return []; }, 'session',
    new AbortController().signal, 1);
  assert.deepEqual(atStart, [], 'nothing is older than the first event');
  assert.equal(startReads, 0, 'the start of the history is not read again');

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
