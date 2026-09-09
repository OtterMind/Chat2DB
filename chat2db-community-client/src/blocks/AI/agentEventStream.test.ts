import assert from 'node:assert/strict';
import type { AgentEvent } from '@/service/agent';
import { followAgentRun, readAgentHistory } from './agentEventStream';
import { buildAgentTranscript } from './agentEvents';

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
}

void main().catch((error) => { console.error(error); process.exitCode = 1; });
