import koApprovals from '@/i18n/ko-KR/stream';
import jaApprovals from '@/i18n/ja-JP/stream';
import esApprovals from '@/i18n/es-ES/stream';
import enApprovals from '@/i18n/en-US/stream';
import zhApprovals from '@/i18n/zh-CN/stream';
import assert from 'node:assert/strict';
import { buildAgentTranscript, mergeAgentEvents, updateAgentApprovals } from './agentEvents';
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

const requested = event(4, 'APPROVAL_REQUESTED', {
  approvalId: 'approval-1', toolName: 'bash', command: "printf 'line 1\\nline 2'", workingDirectory: '/folder with spaces',
});
const requestedAgain = event(5, 'APPROVAL_REQUESTED', requested.payload);
const pendingApprovals = updateAgentApprovals([], [requested, requestedAgain]);
assert.equal(pendingApprovals.length, 1);
assert.equal(pendingApprovals[0].command, requested.payload.command);
assert.equal(pendingApprovals[0].status, 'pending');
const approved = updateAgentApprovals(pendingApprovals, [event(6, 'APPROVAL_DECIDED', { approvalId: 'approval-1', approved: true })]);
assert.equal(approved[0].status, 'approved');
assert.equal(updateAgentApprovals(approved, [requestedAgain])[0].status, 'approved');
const second = event(7, 'APPROVAL_REQUESTED', { ...requested.payload, approvalId: 'approval-2' });
const parallel = updateAgentApprovals(approved, [second, event(8, 'APPROVAL_DECIDED', { approvalId: 'approval-2', approved: false })]);
assert.deepEqual(parallel.map((item) => item.status), ['approved', 'denied']);
const nextRun = { ...second, runId: 'next-run', payload: { ...second.payload, approvalId: 'approval-3' } };
const terminal = updateAgentApprovals(pendingApprovals, [nextRun, event(9, 'RUN_CANCELLED')]);
assert.deepEqual(terminal.map((item) => item.status), ['closed', 'pending']);
assert.deepEqual(updateAgentApprovals([], [requested, event(10, 'APPROVAL_DECIDED', { approvalId: 'approval-1', approved: true })]), approved);
assert.deepEqual(updateAgentApprovals([], [event(4, 'APPROVAL_REQUESTED', { approvalId: 'missing-command' })]), []);

for (const locale of [zhApprovals, enApprovals, esApprovals, jaApprovals, koApprovals]) {
  for (const status of ['pending', 'approved', 'denied', 'closed'] as const) {
    assert.ok(locale[`stream.approval.${status}`]);
  }
  assert.ok(locale['stream.approval.approve']);
  assert.ok(locale['stream.approval.deny']);
}
assert.notEqual(zhApprovals['stream.approval.pending'], enApprovals['stream.approval.pending']);
