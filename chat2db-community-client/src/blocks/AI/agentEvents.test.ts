import koApprovals from '@/i18n/ko-KR/stream';
import jaApprovals from '@/i18n/ja-JP/stream';
import esApprovals from '@/i18n/es-ES/stream';
import enApprovals from '@/i18n/en-US/stream';
import zhApprovals from '@/i18n/zh-CN/stream';
import assert from 'node:assert/strict';
import { appendAgentTimeline, buildAgentTranscript, mergeAgentEvents, updateAgentApprovals } from './agentEvents';
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
  { id: 'assistant-run', runId: 'run', role: 'assistant', content: 'hi', traceEntries: [],
    timeline: [{ kind: 'text', sequence: 3, text: 'hi' }] },
]);

const requested = event(4, 'APPROVAL_REQUESTED', {
  approvalId: 'approval-1', toolName: 'bash', command: "printf 'line 1\\nline 2'", workingDirectory: '/folder with spaces',
});
const requestedAgain = event(5, 'APPROVAL_REQUESTED', requested.payload);
const sqlApproval = event(5, 'APPROVAL_REQUESTED', {
  approvalId: 'sql-approval', toolName: 'db_query', command: 'SELECT 1; UPDATE sales SET amount=2;',
  dataSourceId: '7', dataSourceName: 'Local MySQL', database: 'app', schema: 'tenant',
});
const sqlPending = updateAgentApprovals([], [sqlApproval]);
assert.equal(sqlPending[0].toolName, 'SQL');
assert.equal(sqlPending[0].command, sqlApproval.payload.command);
assert.deepEqual(sqlPending[0].databaseTarget, {
  dataSourceId: '7', dataSourceName: 'Local MySQL', database: 'app', schema: 'tenant',
});
assert.equal(updateAgentApprovals(sqlPending, [event(6, 'APPROVAL_DECIDED', {
  approvalId: 'sql-approval', approved: false,
})])[0].status, 'denied');
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
  for (const key of ['datasource', 'database', 'schema']) assert.ok(locale[`stream.approval.${key}`]);
}
assert.notEqual(zhApprovals['stream.approval.pending'], enApprovals['stream.approval.pending']);

const timelineEvents = [
  event(1, 'RUN_ACCEPTED', { text: 'check attendance' }),
  event(2, 'ASSISTANT_TEXT_DELTA', { text: 'Which ' }),
  event(3, 'ASSISTANT_TEXT_DELTA', { text: 'database?' }),
  event(4, 'QUESTION_REQUESTED', { questionId: 'question-1' }),
  event(5, 'QUESTION_ANSWERED', { questionId: 'question-1', text: 'app' }),
  event(6, 'TOOL_CALL_RUNNING', { toolCallId: 'query-1', toolName: 'db_query', args: { sql: 'SELECT 1' } }),
  event(7, 'TOOL_CALL_COMPLETED', { toolCallId: 'query-1', toolName: 'db_query', result: { rows: [[1]] } }),
  event(8, 'CHART_CREATED', { chart: { id: 'chart-1' } }),
  event(9, 'TOOL_CALL_COMPLETED', { toolCallId: 'chart-tool', toolName: 'render_chart',
    result: { details: { data: { chartId: 'chart-1' } } } }),
  event(10, 'ASSISTANT_TEXT_DELTA', { text: 'Done.' }),
  event(11, 'ASSISTANT_MESSAGE_STARTED'),
  event(12, 'ASSISTANT_TEXT_DELTA', { text: 'Next paragraph.' }),
  event(13, 'RUN_COMPLETED'),
];
const firstChunk = appendAgentTimeline([], timelineEvents.slice(0, 2));
Object.freeze(firstChunk[0]);
const secondChunk = appendAgentTimeline(firstChunk, timelineEvents.slice(2, 5));
assert.deepEqual(firstChunk, [{ kind: 'text', sequence: 2, text: 'Which ' }]);
assert.deepEqual(secondChunk.map((entry) => [entry.sequence, entry.kind]), [[2, 'text'], [4, 'question']]);
const liveTimeline = appendAgentTimeline(secondChunk, timelineEvents.slice(5));
const replayTimeline = buildAgentTranscript([...timelineEvents].reverse())[1].timeline;
assert.deepEqual(liveTimeline, replayTimeline);
assert.deepEqual(liveTimeline.map((entry) => [entry.sequence, entry.kind]), [
  [2, 'text'], [4, 'question'], [6, 'trace'], [7, 'trace'], [8, 'chart'], [9, 'trace'], [10, 'text'],
]);
assert.deepEqual(appendAgentTimeline(liveTimeline, timelineEvents.slice(8)), liveTimeline);
const finalText = liveTimeline.at(-1);
assert.equal(finalText?.kind === 'text' && finalText.text, 'Done.\n\nNext paragraph.');
assert.equal(liveTimeline[5].kind === 'trace' && liveTimeline[5].trace.chartId, 'chart-1');
const reasoning = appendAgentTimeline([], [event(1, 'ASSISTANT_REASONING_DELTA', { text: 'Think ' }),
  event(2, 'ASSISTANT_REASONING_DELTA', { text: 'carefully' })]);
assert.equal(reasoning.length, 1);
assert.equal(reasoning[0].kind === 'trace' && reasoning[0].trace.content, 'Think carefully');
assert.deepEqual(buildAgentTranscript([{ ...event(1, 'ASSISTANT_MESSAGE_STARTED'), runId: undefined }]), []);
for (const locale of [zhApprovals, enApprovals, esApprovals, jaApprovals, koApprovals]) {
  for (const key of ['stream.question.prompt', 'stream.question.answer', 'stream.directory.clear']) assert.ok(locale[key]);
}
