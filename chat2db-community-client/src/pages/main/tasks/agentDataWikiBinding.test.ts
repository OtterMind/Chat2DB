import assert from 'node:assert/strict';

import type { AgentDefinition } from '@/service/agent';

import { agentDataWikiBindings, dataWikiBindingIds } from './agentDataWikiBinding';

const legacyAgent = {
  dataWikiIds: ['wiki-1'],
  dataWikiBindings: [],
} as AgentDefinition;
const legacyBindings = agentDataWikiBindings(legacyAgent);
assert.deepEqual(legacyBindings, [{
  dataWikiId: 'wiki-1',
  maxRows: 200,
  timeoutSeconds: 60,
  approvalMode: 'RISK_BASED',
  allowProduction: false,
}]);

const configuredAgent = {
  dataWikiIds: ['wiki-1'],
  dataWikiBindings: [{
    dataWikiId: 'wiki-1',
    maxRows: 80,
    timeoutSeconds: 12,
    approvalMode: 'ALWAYS',
    allowProduction: true,
  }],
} as AgentDefinition;
const configuredBindings = agentDataWikiBindings(configuredAgent);
assert.equal(configuredBindings[0].maxRows, 80);
assert.deepEqual(dataWikiBindingIds(configuredBindings), ['wiki-1']);
