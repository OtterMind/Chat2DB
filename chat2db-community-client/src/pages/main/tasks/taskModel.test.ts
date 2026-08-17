import assert from 'node:assert/strict';

import type { AgentArtifactDetail, AgentTask } from '@/service/agent';
import {
  setPendingConversationTarget,
  takePendingConversationTarget,
} from '@/utils/conversationNavigation';
import {
  artifactCharts,
  artifactMarkdown,
  artifactTables,
  buildToolActivities,
  cleanAgentMarkdown,
  currentArtifactVersion,
  extractAgentChartPresentation,
  groupTasks,
  TASK_TRANSITIONS,
  upsertTask,
} from './taskModel';

const task = (id: string, status: AgentTask['status']): AgentTask =>
  ({
    id,
    status,
    title: id,
    priority: 0,
    assigneeAgentId: 'agent',
    originType: 'BOARD',
    dataScopeSnapshot: [],
    revision: 1,
  } as unknown as AgentTask);

const groups = groupTasks([
  task('todo', 'TODO'),
  task('running', 'IN_PROGRESS'),
  task('review', 'IN_REVIEW'),
  task('done', 'DONE'),
]);
assert.deepEqual(
  groups.map((group) => group.tasks.map((item) => item.id)),
  [['todo'], ['running'], ['review'], ['done']],
  'task board should group lifecycle states without losing tasks',
);

assert.equal(
  buildToolActivities([
    {
      eventId: 'failed-result',
      runId: 'run',
      sequence: 1,
      type: 'TOOL_RESULT',
      content: 'SQL execution failed: syntax error',
      payload: { name: 'execute_sql', success: false, status: 'FAILED' },
      occurredAt: 3,
    },
  ])[0].status,
  'FAILED',
  'a completed callback with a failed SQL outcome must be presented as failed',
);
assert.deepEqual(TASK_TRANSITIONS.DONE, [], 'terminal tasks must not expose an invalid transition');
assert.deepEqual(
  upsertTask([task('old', 'TODO')], task('new', 'IN_PROGRESS')).map((item) => item.id),
  ['new', 'old'],
  'newly delegated tasks should be inserted into an already-mounted board',
);
assert.equal(
  upsertTask([task('same', 'TODO')], task('same', 'DONE'))[0].status,
  'DONE',
  'existing delegated tasks should be refreshed in place',
);
setPendingConversationTarget({ sessionId: 'source-session', messageId: 'delegation-message' });
assert.equal(
  takePendingConversationTarget('another-session'),
  undefined,
  'a pending source anchor must not be consumed by another conversation',
);
assert.deepEqual(
  takePendingConversationTarget('source-session'),
  { sessionId: 'source-session', messageId: 'delegation-message' },
  'the source conversation should consume its delegated-message anchor once',
);
assert.equal(
  takePendingConversationTarget('source-session'),
  undefined,
  'a consumed source anchor must not affect later navigation',
);

const artifact = {
  artifact: { id: 'artifact', currentVersion: 2 },
  versions: [
    { version: 1, content: { blocks: [{ type: 'MARKDOWN', content: 'old' }] } },
    {
      version: 2,
      content: {
        blocks: [{ type: 'MARKDOWN', content: '# Result' }],
        charts: [{ chartType: 'Column', data: [{ month: 'Jan', value: 3 }] }],
        tables: [{ columns: ['month', 'value'], rows: [{ month: 'Jan', value: 3 }] }],
      },
    },
  ],
} as unknown as AgentArtifactDetail;
assert.equal(currentArtifactVersion(artifact)?.version, 2);
assert.equal(artifactMarkdown(artifact), '# Result');
assert.equal(artifactCharts(artifact).length, 1);
assert.equal(artifactTables(artifact).length, 1);

const chartPresentation = extractAgentChartPresentation(`图表展示
pie title OneAPI 渠道类型分布（共6个）
    "智谱 GLM (type=14)" : 5
    "MiniMax (type=27)" : 1
xychart-beta
    title "各渠道配置情况"
    x-axis [GLM-PRO, MiniMax, GLM-MAX-3]
    y-axis "渠道" 0 --> 6
    bar [1, 1, 1]`);
assert.equal(chartPresentation.markdown, '');
assert.deepEqual(chartPresentation.charts, [
  {
    chartType: 'Pie',
    angleField: 'category',
    valueField: 'value',
    title: 'OneAPI 渠道类型分布（共6个）',
    data: [
      { category: '智谱 GLM (type=14)', value: 5 },
      { category: 'MiniMax (type=27)', value: 1 },
    ],
  },
  {
    chartType: 'Column',
    xField: 'category',
    yField: 'value',
    title: '各渠道配置情况',
    data: [
      { category: 'GLM-PRO', value: 1 },
      { category: 'MiniMax', value: 1 },
      { category: 'GLM-MAX-3', value: 1 },
    ],
  },
]);

assert.equal(
  cleanAgentMarkdown('准备调用。\n\n<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name="list_all_datasources"></｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>'),
  '准备调用。',
  'pseudo tool-call protocol text must not leak into rendered markdown',
);
assert.deepEqual(
  buildToolActivities([
    {
      eventId: 'call-event',
      runId: 'run',
      sequence: 1,
      type: 'TOOL_CALL',
      content: 'list_all_datasources',
      payload: { toolCallId: 'call-1', name: 'list_all_datasources', arguments: '{}' },
      occurredAt: 1,
    },
    {
      eventId: 'result-event',
      runId: 'run',
      sequence: 2,
      type: 'TOOL_RESULT',
      content: 'id=7; name=local',
      payload: { toolCallId: 'call-1', name: 'list_all_datasources' },
      occurredAt: 2,
    },
  ]),
  [{ id: 'call-1', name: 'list_all_datasources', arguments: '{}', result: 'id=7; name=local', occurredAt: 1, status: 'COMPLETED' }],
);
assert.deepEqual(
  buildToolActivities([
    {
      eventId: 'dsh-call-event',
      runId: 'run',
      sequence: 3,
      type: 'TOOL_CALL',
      content: 'bash: call-dsh-1',
      payload: {
        event: { data: { callId: 'call-dsh-1', name: 'bash', arguments: '{"command":"pwd"}' } },
      },
      occurredAt: 3,
    },
    {
      eventId: 'dsh-result-event',
      runId: 'run',
      sequence: 4,
      type: 'TOOL_RESULT',
      content: '',
      payload: {
        event: {
          data: {
            message: {
              source: { kind: 'tool', callId: 'call-dsh-1' },
              content: [{
                type: 'tool-result',
                toolCallId: 'call-dsh-1',
                content: [{ type: 'text', text: '/workspace' }],
                isError: false,
              }],
            },
          },
        },
      },
      occurredAt: 4,
    },
  ]),
  [{
    id: 'call-dsh-1',
    name: 'bash',
    arguments: '{"command":"pwd"}',
    result: '/workspace',
    occurredAt: 3,
    status: 'COMPLETED',
  }],
  'historical DSH tool events should expose nested arguments and results',
);

console.log('Task presentation model tests passed.');
