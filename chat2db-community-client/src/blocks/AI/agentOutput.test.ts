import assert from 'node:assert/strict';
import { agentEventTrace, buildAgentTranscript } from './agentEvents';
import { formatToolResult, parseOutputReference, toolOutputItems } from './agentOutput';
import { toolExecutions, toolSummary } from './components/AgentV2Session/presentation';
import type { AgentEvent } from '@/service/agent';
import zh from '@/i18n/zh-CN/stream';
import en from '@/i18n/en-US/stream';
import ja from '@/i18n/ja-JP/stream';
import ko from '@/i18n/ko-KR/stream';
import es from '@/i18n/es-ES/stream';

const output = { mode: 'file', artifactId: 'out-1', path: '/system/results/out-1.jsonl',
  format: 'jsonl', sizeBytes: 900_000, complete: true, previewTruncated: true } as const;
const event = (sequence: number, type: AgentEvent['type'], payload: AgentEvent['payload']): AgentEvent => ({
  id: `event-${sequence}`, sessionId: 'session', runId: 'run', sequence, type, payload, occurredAt: '',
});
const started = event(2, 'TOOL_CALL_RUNNING', {
  toolCallId: 'call', toolName: 'db_query', args: { description: 'Load rows', sql: 'select * from sample' },
});
const finished = event(3, 'TOOL_CALL_COMPLETED', {
  toolCallId: 'call', toolName: 'db_query', durationMs: 25,
  result: { content: [{ type: 'text', text: JSON.stringify({ ok: true, data: { rows: [[1, null]] }, output }) }],
    details: { output } },
});
const trace = agentEventTrace(finished)!;
assert.deepEqual(trace.outputs, [{ output }]);
const contentOnly = structuredClone(finished);
delete (contentOnly.payload.result as Record<string, unknown>).details;
assert.deepEqual(agentEventTrace(contentOnly)?.outputs, [{ output }], 'Replayed content envelopes retain their file reference');
assert.deepEqual(JSON.parse(formatToolResult(trace.content!)), { ok: true, data: { rows: [[1, null]] }, output },
  'Displayed tool results must retain the file reference received by the model');
const tools = toolExecutions([agentEventTrace(started)!, trace]);
assert.equal(tools.length, 1);
assert.equal(tools[0].description, 'Load rows');
assert.deepEqual(tools[0].outputs, [{ output }]);
assert.deepEqual(toolSummary([agentEventTrace(started)!, trace]), { count: 1, durationMs: 25 });
const history = buildAgentTranscript([event(1, 'RUN_ACCEPTED', { text: 'query rows' }), started, finished,
  event(4, 'RUN_COMPLETED', {})]);
assert.deepEqual(history[1].traceEntries.find((entry) => entry.type === 'tool_result')?.outputs, [{ output }]);

const multiple = { ok: true, data: { results: [
  { columns: ['small'], rows: [[1]] },
  { columns: ['large'], rows: [['preview']], output },
  { columns: ['other'], rows: [['preview']], output: { ...output, artifactId: 'out-2' } },
] } };
assert.deepEqual(toolOutputItems(JSON.stringify(multiple)), [
  { output, resultIndex: 2 }, { output: { ...output, artifactId: 'out-2' }, resultIndex: 3 },
]);
const multipleFinished = structuredClone(finished);
multipleFinished.payload.result = {
  content: [{ type: 'text', text: JSON.stringify(multiple) }], details: multiple,
};
const multipleTrace = agentEventTrace(multipleFinished)!;
assert.equal(multipleTrace.outputs?.length, 2, 'Content and details references must not duplicate attachments');
assert.deepEqual(toolSummary([agentEventTrace(started)!, multipleTrace]), { count: 1, durationMs: 25 });
assert.deepEqual(JSON.parse(formatToolResult(JSON.stringify(multiple))), multiple,
  'Each statement keeps its own output reference in the displayed JSON');

const paged = { ok: true, page: { number: 2, size: 200, returned: 200, hasMore: true, nextPage: 3 },
  data: { results: [{ data: { columns: ['id', 'message'], rows: [['1', 'preview'], ['2']] }, output }] }, output };
assert.deepEqual(JSON.parse(formatToolResult(JSON.stringify(paged))), paged,
  'Preview truncation, file completeness and SQL pagination must remain visible together');

assert.equal(parseOutputReference({ ...output, sizeBytes: -1 }), undefined);
assert.equal(parseOutputReference({ ...output, artifactId: '' }), undefined);
assert.equal(parseOutputReference({ path: 'user supplied path' }), undefined);
assert.deepEqual(parseOutputReference({ mode: 'unavailable', warning: 'Disk full' }), {
  mode: 'unavailable', warning: 'Disk full', complete: false, previewTruncated: true,
});
assert.equal(formatToolResult('ordinary tool text'), 'ordinary tool text');
assert.deepEqual(JSON.parse(formatToolResult('{"output":"business value"}')), { output: 'business value' });

let messages: Record<string, string> = zh;
const translate = (key: string) => messages[key] || key;
assert.equal(translate('stream.output.view'), '查看内容');
messages = en;
assert.equal(translate('stream.output.view'), 'View content');
for (const locale of [zh, en, ja, ko, es]) {
  for (const key of Object.keys(zh).filter((candidate) => candidate.startsWith('stream.output.'))) assert.ok(locale[key]);
}
console.log('Output presentation tests passed: preview, history, chronology, invalid references and locales');
