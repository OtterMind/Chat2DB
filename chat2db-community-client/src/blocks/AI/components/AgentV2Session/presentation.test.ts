import assert from 'node:assert/strict';
import { appendAgentTimeline, type AgentTimelineEntry, type AgentTraceEntry } from '../../agentEvents';
import { getAgentActivity, splitSkillMessage, toolSummary, toolExecutions } from './presentation';
import { timelineSections } from './timelineSections';
import type { AgentQuestionItem } from '../../agentQuestions';
import zh from '@/i18n/zh-CN/stream';
import en from '@/i18n/en-US/stream';
import ja from '@/i18n/ja-JP/stream';
import ko from '@/i18n/ko-KR/stream';
import es from '@/i18n/es-ES/stream';

for (const content of ['/skill:chart 看看出勤数据', '  /skill:chart\n\n保留段落', '/skill:custom-report']) {
  const parts = splitSkillMessage(content)!;
  assert.equal(parts.prefix + parts.command + parts.text, content);
}
for (const content of ['解释 /skill:chart', '/skill:chart.json', '/skill:chart/other', '普通问题', '/skill:']) {
  assert.equal(splitSkillMessage(content), undefined);
}
const tool = (sequence: number, id: string, name: string): AgentTimelineEntry => ({
  sequence, kind: 'trace', trace: { type: 'tool_call', id, name },
});
const done = (sequence: number, id: string, failed = false): AgentTimelineEntry => ({
  sequence, kind: 'trace', trace: { type: 'tool_result', id, failed },
});
const activity = (entries: AgentTimelineEntry[], active = true) => getAgentActivity(active, entries, 'run', [], []);
assert.deepEqual(activity([]), { kind: 'starting' });
assert.deepEqual(getAgentActivity(true, [], undefined, [], []), { kind: 'starting' },
  'A send shows thinking before the server has assigned a run id');
assert.equal(activity([], false), undefined);
const beforeToken = appendAgentTimeline([], [
  { id: 'accepted', sessionId: 'session', runId: 'run', sequence: 1,
    type: 'RUN_ACCEPTED', payload: {}, occurredAt: '' },
  { id: 'message', sessionId: 'session', runId: 'run', sequence: 2,
    type: 'ASSISTANT_MESSAGE_STARTED', payload: {}, occurredAt: '' },
  { id: 'empty', sessionId: 'session', runId: 'run', sequence: 3,
    type: 'ASSISTANT_REASONING_DELTA', payload: { delta: '' }, occurredAt: '' },
]);
assert.deepEqual(activity(beforeToken), { kind: 'starting' },
  'Run metadata, message start and an empty delta do not count as a first token');
for (const type of ['ASSISTANT_TEXT_DELTA', 'ASSISTANT_REASONING_DELTA']) {
  const afterToken = appendAgentTimeline(beforeToken, [
    { id: 'token', sessionId: 'session', runId: 'run', sequence: 4,
      type, payload: { delta: '查' }, occurredAt: '' },
  ]);
  assert.equal(activity(afterToken), undefined, `${type} ends the initial thinking indicator`);
  const nextMessage = appendAgentTimeline(afterToken, [
    { id: 'next', sessionId: 'session', runId: 'run', sequence: 5,
      type: 'ASSISTANT_MESSAGE_STARTED', payload: {}, occurredAt: '' },
  ]);
  assert.equal(activity(nextMessage), undefined, 'Another model message in the same run does not restart thinking');
}
assert.deepEqual(activity([]), { kind: 'starting' }, 'A fresh run starts thinking again after the timeline resets');
const calls = [tool(1, 'first', 'db_query'), tool(2, 'second', 'read')];
const described = [{ sequence: 1, kind: 'trace' as const, trace: { type: 'tool_call' as const, id: 'described', name: 'db_query', description: '查询数据库中的数据' } }];
assert.deepEqual(getAgentActivity(true, described, 'run', [], []), { kind: 'tool', tool: { name: 'db_query', description: '查询数据库中的数据' } });
assert.deepEqual(toolSummary(described.map((entry) => entry.trace)), { count: 1, durationMs: undefined });
const completedTool: AgentTraceEntry[] = [
  described[0].trace,
  { type: 'tool_result', id: 'described', name: 'db_query', durationMs: 12 },
];
assert.deepEqual(toolSummary(completedTool), { count: 1, durationMs: 12 });
assert.deepEqual(activity(calls), { kind: 'tool', tool: { name: 'read' } });
assert.deepEqual(activity([...calls, done(3, 'second')]), { kind: 'tool', tool: { name: 'db_query' } });
assert.deepEqual(activity([...calls, done(3, 'second'), done(4, 'first', true)]), { kind: 'starting' });
assert.equal(activity([done(1, 'restored-result')]), undefined,
  'A restored result without its call does not look like an active wait');
assert.equal(activity(calls, false), undefined);
assert.equal(activity([{ sequence: 5, kind: 'text', text: 'Answer' }]), undefined);
const question: AgentQuestionItem = { id: 'q', sessionId: 'session', runId: 'run', question: 'Which one?', options: [], status: 'pending' };
assert.deepEqual(getAgentActivity(true, calls, 'run', [question], []), { kind: 'question' });
assert.deepEqual(getAgentActivity(true, calls, 'run', [{ ...question, status: 'answered' }], []), {
  kind: 'tool', tool: { name: 'read' },
});
assert.equal(getAgentActivity(false, calls, 'run', [question], []), undefined);
assert.deepEqual(getAgentActivity(true, [], 'run', [], [{ id: 'a', sessionId: 'session', runId: 'run',
  toolName: 'SQL', command: 'UPDATE t SET x=1', workingDirectory: '', status: 'pending' }]), { kind: 'approval' });
assert.deepEqual(getAgentActivity(true, [], 'other', [question], []), { kind: 'starting' },
  'A pending question from an older run does not suppress the new run indicator');
assert.deepEqual(getAgentActivity(true, [], 'run', [question], [], true), { kind: 'cancelling' });
assert.equal(getAgentActivity(false, [], 'run', [question], [], true), undefined,
  'A terminal run does not keep a thinking, waiting or cancelling indicator');
for (const kind of ['question', 'approval', 'chart'] as const) {
  assert.equal(activity([{ sequence: 1, kind, id: 'card' }]), undefined,
    'Existing interaction and result cards are not pre-token waiting states');
}
assert.equal(activity([{ sequence: 1, kind: 'trace', trace: { type: 'error', content: 'Failed' } }]), undefined);
const live = appendAgentTimeline([], [{ id: 'start', sessionId: 'session', runId: 'run', sequence: 1,
  type: 'TOOL_CALL_RUNNING', payload: { toolCallId: 'call', toolName: 'read', args: { description: '读取技能文件' } }, occurredAt: '' }]);
assert.deepEqual(activity(live), { kind: 'tool', tool: { name: 'read', description: '读取技能文件' } });
const finished = appendAgentTimeline(live, [{ id: 'end', sessionId: 'session', runId: 'run', sequence: 2,
  type: 'TOOL_CALL_COMPLETED', payload: { toolCallId: 'call', toolName: 'read', result: {} }, occurredAt: '' }]);
assert.deepEqual(activity(finished), { kind: 'starting' },
  'A completed tool shows the waiting-for-model state until the next token arrives');
const nextTool = appendAgentTimeline(finished, [{ id: 'next-tool', sessionId: 'session', runId: 'run', sequence: 3,
  type: 'TOOL_CALL_RUNNING', payload: { toolCallId: 'query', toolName: 'db_query', args: { description: '统计每月支付金额' } }, occurredAt: '' }]);
assert.deepEqual(activity(nextTool), { kind: 'tool', tool: { name: 'db_query', description: '统计每月支付金额' } },
  'Progress switches from the completed tool to the current tool request description');
for (const locale of [zh, en, ja, ko, es]) {
  assert.ok(locale['stream.activity.starting']);
  assert.ok(locale['stream.activity.tool']);
  assert.ok(locale['stream.activity.responding']);
}
assert.notEqual(zh['stream.activity.tool'], en['stream.activity.tool']);
console.log('Skill message preservation and active/waiting/completed timeline states passed');

assert.deepEqual(toolSummary([...completedTool, completedTool[1]]), { count: 1, durationMs: 12 });
assert.deepEqual(toolSummary([completedTool[1]]), { count: 1, durationMs: 12 });
assert.deepEqual(toolSummary([...completedTool, { type: 'tool_result', id: 'failed', failed: true, durationMs: 5 }]), { count: 2, durationMs: 17 });
assert.equal(toolSummary([{ type: 'reasoning', content: 'Thinking' }]), undefined);

for (const locale of [zh, en, ja, ko, es]) {
  for (const command of ['new', 'model', 'tools', 'copy', 'export', 'help'] as const) {
    assert.ok(locale[`stream.command.${command}`]);
  }
  assert.ok(locale['stream.trace.toolsSummary'].includes('{1}'));
  assert.ok(locale['stream.trace.toolsSummary'].includes('{2}'));
}
const summaryText = (locale: typeof zh | typeof en) => locale['stream.trace.toolsSummary'].replace('{1}', '2').replace('{2}', '17');
assert.equal(summaryText(zh), '调用了 2 个工具 · 耗时 17ms');
assert.equal(summaryText(en), 'Called 2 tool(s) · 17ms');

const betweenTools: AgentTimelineEntry[] = [...finished, { sequence: 3, kind: 'trace', trace: { type: 'reasoning', content: 'next step' } }, { sequence: 4, kind: 'text', text: 'Next step' }];
assert.equal(activity(betweenTools), undefined);
assert.equal(activity(betweenTools, false), undefined);
assert.deepEqual(getAgentActivity(true, betweenTools, 'run', [], [], true), { kind: 'cancelling' });
const mergedTool = toolExecutions(completedTool);
assert.equal(mergedTool.length, 1);
assert.equal(mergedTool[0].description, '查询数据库中的数据');
assert.equal(mergedTool[0].completed, true);
assert.equal(mergedTool[0].durationMs, 12);

const ordered: AgentTimelineEntry[] = [
  { sequence: 1, kind: 'text', text: 'Searching' },
  tool(2, 'query', 'db_query'),
  tool(3, 'question', 'askUserQuestion'),
  { sequence: 4, kind: 'question', id: 'question-card' },
  done(5, 'question'),
  { sequence: 6, kind: 'text', text: 'Answer received' },
  tool(7, 'chart', 'render_chart'),
  { sequence: 8, kind: 'chart', id: 'chart-card' },
  done(9, 'chart'),
  done(10, 'query'),
  { sequence: 11, kind: 'approval', id: 'approval-card' },
  tool(12, 'next', 'read'),
  done(13, 'next', true),
];
const sections = timelineSections(ordered);
assert.deepEqual(sections.map(({ sequence, kind }) => [sequence, kind]), [
  [1, 'text'], [2, 'tools'], [4, 'question'], [6, 'text'], [7, 'tools'], [8, 'chart'], [11, 'approval'], [12, 'tools'],
]);
assert.equal(sections[2], ordered[3], 'The question keeps its identity when an answer and result arrive');
const groups = sections.filter((section) => section.kind === 'tools');
assert.deepEqual(groups.map((section) => toolSummary(section.entries)?.count), [2, 1, 1]);
assert.deepEqual(groups[0].entries.map(({ id, type }) => [id, type]), [
  ['query', 'tool_call'], ['question', 'tool_call'], ['question', 'tool_result'], ['query', 'tool_result'],
]);
const beforeAnswer = timelineSections(ordered.slice(0, 4));
assert.deepEqual(beforeAnswer.map(({ sequence, kind }) => [sequence, kind]),
  sections.slice(0, 3).map(({ sequence, kind }) => [sequence, kind]));
assert.equal(beforeAnswer[1].kind === 'tools' && beforeAnswer[1].entries.length, 2,
  'Incremental grouping does not mutate a previous render');
assert.deepEqual(timelineSections([done(1, 'historical-result')]).map((section) => section.kind), ['tools']);
assert.deepEqual(timelineSections([{ sequence: 1, kind: 'trace', trace: { type: 'reasoning' } }]), []);
