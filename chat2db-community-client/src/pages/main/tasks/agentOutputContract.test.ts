import assert from 'node:assert/strict';
import { parseAgentOutputContract, serializeAgentOutputContract } from './agentOutputContract';

assert.deepEqual(parseAgentOutputContract(undefined), {
  outputRequirements: [{ type: 'REPORT', min: 1 }],
  outputRequiredSections: [],
  extras: {},
});

const parsed = parseAgentOutputContract(JSON.stringify({
  requiredArtifacts: [{ type: 'CHART', min: 2 }, { type: 'DATA_TABLE' }],
  requiredSections: ['摘要', '结论'],
  version: 1,
}));
assert.deepEqual(parsed.outputRequirements, [
  { type: 'CHART', min: 2 },
  { type: 'DATA_TABLE', min: 1 },
]);
assert.deepEqual(parsed.outputRequiredSections, ['摘要', '结论']);
assert.deepEqual(parsed.extras, { version: 1 });

assert.deepEqual(JSON.parse(serializeAgentOutputContract(
  [{ type: 'REPORT', min: 1 }, { type: 'REPORT', min: 2 }],
  [' 摘要 ', '摘要', '结论'],
  { version: 1 },
)), {
  version: 1,
  requiredArtifacts: [{ type: 'REPORT', min: 2 }],
  requiredSections: ['摘要', '结论'],
});

console.log('Agent output contract tests passed.');
