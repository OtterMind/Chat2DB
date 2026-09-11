import assert from 'node:assert/strict';
import { captureAgentContext, agentContextDatabaseType, agentContextSummary } from './agentContext';
import { buildAgentTranscript } from './agentEvents';
import type { AgentContextObject } from '@/types/agentContext';
import type { AgentEvent } from '@/service/agent';
import type { IBoundInfo } from '@/typings';
import { DatabaseTypeCode } from '@/constants/common';

const selection: IBoundInfo = { dataSourceId: 123, dataSourceName: 'localhost', databaseType: DatabaseTypeCode.MYSQL, databaseName: 'sales', schemaName: 'public' };
const table = { ...selection, tableName: 'orders' };
const mention: AgentContextObject = {
  dataSourceId: '123', dataSourceName: 'localhost', databaseType: 'MYSQL', database: 'sales', schema: 'public',
  type: 'TABLE', name: 'orders', source: 'MENTION',
};
const snapshot = captureAgentContext(selection, table, [mention], 'Asia/Shanghai');
assert.deepEqual(snapshot.objects, [mention]);
selection.databaseName = 'other';
mention.name = 'changed';
assert.equal(snapshot.selection?.database, 'sales');
assert.equal(snapshot.objects[0].name, 'orders');
assert.deepEqual(captureAgentContext(selection, table, [mention], 'UTC').objects, []);
assert.deepEqual(captureAgentContext(null, table, [mention], 'UTC'), { timeZone: 'UTC', selection: null, objects: [] });
assert.equal(captureAgentContext(table, table, [], 'UTC').objects[0].source, 'CURRENT_TABLE');
assert.equal(captureAgentContext(table, { ...table, tableName: undefined, viewName: 'summary' }, [], 'UTC').objects[0].type, 'VIEW');
const context = { ...snapshot, objects: snapshot.objects.map((item) => ({ ...item, dataSourceName: 'MySQL' })) };
assert.equal(agentContextSummary(context), 'MySQL / sales / public / orders');
assert.equal(snapshot.selection?.databaseType, 'MYSQL');
assert.equal(agentContextDatabaseType(context), 'MYSQL');
const event: AgentEvent = { id: 'one', sessionId: 'session', runId: 'run', sequence: 1,
  type: 'RUN_ACCEPTED', payload: { text: '用户原文', context, renderedPrompt: '<chat2db_context>internal</chat2db_context>' }, occurredAt: '' };
const [message] = buildAgentTranscript([event]);
assert.equal(message.content, '用户原文');
assert.equal(message.contextSummary, 'MySQL / sales / public / orders');
assert.equal(buildAgentTranscript([{ ...event, payload: { text: 'legacy' } }])[0].content, 'legacy');
console.log('Agent context snapshot, scope, references and history passed.');
