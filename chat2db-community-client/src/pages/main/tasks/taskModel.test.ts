import assert from 'node:assert/strict';

import type { AgentArtifactDetail, AgentTask } from '@/service/agent';
import {
  artifactCharts,
  artifactMarkdown,
  artifactTables,
  buildToolActivities,
  cleanAgentMarkdown,
  currentArtifactVersion,
  groupTasks,
  TASK_TRANSITIONS,
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
assert.deepEqual(TASK_TRANSITIONS.DONE, [], 'terminal tasks must not expose an invalid transition');

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

console.log('Task presentation model tests passed.');
