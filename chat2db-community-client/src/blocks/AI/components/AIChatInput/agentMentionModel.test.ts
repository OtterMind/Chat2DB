import assert from 'node:assert/strict';

import type { AgentDataScope } from '@/service/agent';
import { removeAgentMention, resolveMentionTaskScopes } from './agentMentionModel';

const scopes: AgentDataScope[] = [
  {
    dataSourceId: 1,
    databaseName: 'sales',
    schemaName: 'public',
    tableNames: [],
    excludedTableNames: ['secret'],
  },
  { dataSourceId: 2, tableNames: [], excludedTableNames: [] },
];

assert.deepEqual(
  resolveMentionTaskScopes(scopes, { dataSourceId: 1, databaseName: 'sales', schemaName: 'public' }).map(
    (scope) => scope.dataSourceId,
  ),
  [1],
  'chat context should narrow a task to matching agent scopes',
);
assert.deepEqual(
  resolveMentionTaskScopes(scopes, null),
  scopes,
  'an unbound chat should keep the agent policy snapshot',
);
assert.equal(removeAgentMention('@Sales Analyst analyze weekly revenue', 'Sales Analyst'), 'analyze weekly revenue');
assert.equal(removeAgentMention('analyze @Sales Analyst weekly revenue', 'Sales Analyst'), 'analyze weekly revenue');

console.log('Agent mention model tests passed.');
