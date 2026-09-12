import assert from 'node:assert/strict';
import { appendAgentTimeline, type AgentTimelineEntry, type AgentTraceEntry } from '../../agentEvents';
import { getAgentActivity, splitSkillMessage, toolSummary, toolExecutions } from './presentation';
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
assert.deepEqual(activity([...calls, done(3, 'second')]), { kind: 'tool', tool: { name: 'read' } });
assert.deepEqual(activity([...calls, done(3, 'second'), done(4, 'first', true)]), { kind: 'tool', tool: { name: 'read' } });
assert.equal(activity(calls, false), undefined);
assert.deepEqual(activity([{ sequence: 5, kind: 'text', text: 'Answer' }]), { kind: 'starting' });
const question: AgentQuestionItem = { id: 'q', sessionId: 'session', runId: 'run', question: 'Which one?', options: [], status: 'pending' };
assert.deepEqual(getAgentActivity(true, calls, 'run', [question], []), { kind: 'question' });
assert.deepEqual(getAgentActivity(true, calls, 'run', [{ ...question, status: 'answered' }], []), {
  kind: 'tool', tool: { name: 'read' },
});
assert.equal(getAgentActivity(false, calls, 'run', [question], []), undefined);
assert.deepEqual(getAgentActivity(true, [], 'run', [], [{ id: 'a', sessionId: 'session', runId: 'run',
  toolName: 'SQL', command: 'UPDATE t SET x=1', workingDirectory: '', status: 'pending' }]), { kind: 'approval' });
assert.deepEqual(getAgentActivity(true, [], 'other', [question], []), { kind: 'starting' });
const live = appendAgentTimeline([], [{ id: 'start', sessionId: 'session', runId: 'run', sequence: 1,
  type: 'TOOL_CALL_RUNNING', payload: { toolCallId: 'call', toolName: 'read', args: { description: '读取技能文件' } }, occurredAt: '' }]);
assert.deepEqual(activity(live), { kind: 'tool', tool: { name: 'read', description: '读取技能文件' } });
const finished = appendAgentTimeline(live, [{ id: 'end', sessionId: 'session', runId: 'run', sequence: 2,
  type: 'TOOL_CALL_COMPLETED', payload: { toolCallId: 'call', toolName: 'read', result: {} }, occurredAt: '' }]);
assert.deepEqual(activity(finished), { kind: 'tool', tool: { name: 'read', description: '读取技能文件' } });
for (const locale of [zh, en, ja, ko, es]) {
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
assert.deepEqual(activity(betweenTools), activity(live));
assert.equal(activity(betweenTools, false), undefined);
assert.deepEqual(getAgentActivity(true, betweenTools, 'run', [], [], true), { kind: 'cancelling' });
const mergedTool = toolExecutions(completedTool);
assert.equal(mergedTool.length, 1);
assert.equal(mergedTool[0].description, '查询数据库中的数据');
assert.equal(mergedTool[0].completed, true);
assert.equal(mergedTool[0].durationMs, 12);
