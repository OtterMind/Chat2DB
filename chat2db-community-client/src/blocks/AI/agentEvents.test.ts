import assert from 'node:assert/strict';
import { buildAgentTranscript, mergeAgentEvents } from './agentEvents';
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
  [
    event(2, 'ASSISTANT_MESSAGE_STARTED'),
    event(3, 'ASSISTANT_TEXT_DELTA', { assistantMessageEvent: { type: 'text_delta', delta: 'hi' } }),
  ],
);
assert.deepEqual(merged.map((item) => item.sequence), [1, 2, 3]);
assert.deepEqual(buildAgentTranscript(merged), [
  { id: 'user-event-1', runId: 'run', role: 'user', content: 'hello', traceEntries: [] },
  { id: 'assistant-run', runId: 'run', role: 'assistant', content: 'hi', traceEntries: [] },
]);
