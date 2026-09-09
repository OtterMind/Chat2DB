import assert from 'node:assert/strict';
import { buildAgentTranscript, mergeAgentEvents } from './model';
import type { AgentEvent } from '@/service/agent';

const event = (sequence: number, type: AgentEvent['type'], payload: Record<string, unknown> = {}): AgentEvent => ({
  id: `event-${sequence}`,
  sessionId: 'session',
  runId: 'run',
  sequence,
  type,
  payload,
  occurredAt: '',
});

const merged = mergeAgentEvents(
  [event(1, 'RUN_ACCEPTED', { text: 'hello' }), event(2, 'ASSISTANT_MESSAGE_STARTED')],
  [event(2, 'ASSISTANT_MESSAGE_STARTED'), event(3, 'ASSISTANT_TEXT_DELTA', { delta: { text: 'hi' } })],
);
assert.deepEqual(merged.map((item) => item.sequence), [1, 2, 3]);
assert.deepEqual(buildAgentTranscript(merged), [
  { id: 'user-event-1', runId: 'run', role: 'user', content: 'hello' },
  { id: 'assistant-run', runId: 'run', role: 'assistant', content: 'hi' },
]);
