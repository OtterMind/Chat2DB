import assert from 'node:assert/strict';
import { ChatSourceType, QuestionType } from '@/constants/chat';
import { DatabaseTypeCode } from '@/constants/common';
import type { AgentContextObject } from '@/types/agentContext';
import type { IBoundInfo } from '@/typings';
import { buildAgentEntryContext, buildAgentEntryDetail } from './agentEntry';

const selection: IBoundInfo = {
  dataSourceId: 123,
  dataSourceName: 'localhost',
  databaseType: DatabaseTypeCode.MYSQL,
  databaseName: 'sales',
  schemaName: 'public',
};
const table: IBoundInfo = { ...selection, tableName: 'orders' };
const foreignMention: AgentContextObject = {
  dataSourceId: '999',
  database: 'other',
  schema: null,
  type: 'TABLE',
  name: 'elsewhere',
  source: 'MENTION',
};

// A context-menu action carries the editor binding as its anchor and the selection as its payload.
const fromEditor = buildAgentEntryDetail({
  intent: QuestionType.SQL_EXPLAIN,
  input: 'explain: select 1',
  scope: selection,
  currentTable: table,
  payload: { sql: 'select 1' },
});
assert.deepEqual(fromEditor.agentContext.selection, {
  dataSourceId: '123',
  dataSourceName: 'localhost',
  databaseType: 'MYSQL',
  database: 'sales',
  schema: 'public',
});
assert.deepEqual(
  fromEditor.agentContext.objects.map((object) => `${object.type}:${object.name}:${object.source}`),
  ['TABLE:orders:CURRENT_TABLE'],
);
assert.equal(fromEditor.questionType, QuestionType.SQL_EXPLAIN);
assert.equal(fromEditor.sql, 'select 1');
assert.equal(fromEditor.dataSourceId, 123);
assert.equal(fromEditor.databaseName, 'sales');
assert.equal(fromEditor.source, ChatSourceType.DATASOURCE_CHAT);

// A diagnose entry has no current table, so it keeps the anchor and reports no objects.
const diagnose = buildAgentEntryDetail({
  intent: QuestionType.SQL_DEBUG,
  input: 'debug: select',
  scope: selection,
  payload: { sql: 'select', errorMessage: 'boom' },
});
assert.deepEqual(diagnose.agentContext.objects, []);
assert.equal(diagnose.agentContext.selection?.dataSourceId, '123');
assert.equal(diagnose.sql, 'select');

// Mentions outside the anchor scope are dropped exactly as the chat input drops them.
const withMention = buildAgentEntryContext({
  intent: QuestionType.ORDINARY_CHAT,
  input: 'x',
  scope: selection,
  currentTable: table,
  mentions: [foreignMention],
});
assert.deepEqual(withMention.objects.map((object) => object.name), ['orders']);

// An entry without an anchor must not fabricate one.
const bare = buildAgentEntryDetail({ intent: QuestionType.ORDINARY_CHAT, input: 'hi' });
assert.equal(bare.agentContext.selection, null);
assert.deepEqual(bare.agentContext.objects, []);
assert.equal(bare.dataSourceId, undefined);
assert.equal(bare.sql, undefined);

console.log('Agent entry slots passed.');
