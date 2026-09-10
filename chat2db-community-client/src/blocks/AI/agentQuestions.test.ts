import assert from 'node:assert/strict';
import type { AgentEvent } from '../../service/agent';
import { updateAgentQuestions } from './agentQuestions';

const event = (type: AgentEvent['type'], payload: AgentEvent['payload'], runId = 'run', sessionId = 'session'): AgentEvent => ({
  id: `${type}-${runId}`, sessionId, runId, type, sequence: 1, occurredAt: '', payload,
});
const requested = event('QUESTION_REQUESTED', { questionId: 'q', question: 'Choose', options: [
  { id: 'a', label: 'Same label', description: 'First scope' }, { id: 'b', label: 'Same label', description: 'Second scope' },
] });
let items = updateAgentQuestions([], [requested, requested]);
assert.equal(items.length, 1);
assert.equal(items[0].status, 'pending');
assert.deepEqual(updateAgentQuestions(items, [event('QUESTION_ANSWERED', { questionId: 'q', answer: { optionId: 'b' } }, 'different')]), items);
const answered = event('QUESTION_ANSWERED', { questionId: 'q', answer: { questionId: 'q', optionId: 'b', optionLabel: 'Same label', text: 'Detail' } });
items = updateAgentQuestions(items, [answered, event('RUN_COMPLETED', {})]);
assert.equal(items[0].status, 'answered'); assert.equal(items[0].answer?.optionId, 'b');
assert.equal(items[0].answer?.text, 'Detail');
assert.deepEqual(updateAgentQuestions([], [requested, answered, event('RUN_COMPLETED', {})]), items);
const closed = updateAgentQuestions([], [requested, event('RUN_CANCELLED', {})]);
assert.equal(closed[0].status, 'closed');
assert.equal(updateAgentQuestions([], [requested, event('QUESTION_CLOSED', { questionId: 'q' })])[0].status, 'closed');
assert.equal(updateAgentQuestions([], [requested, event('RUN_COMPLETED', {}, 'other')])[0].status, 'pending');
const textOnly = updateAgentQuestions([], [requested, event('QUESTION_ANSWERED', { questionId: 'q', answer: { text: 'Custom answer' } })]);
assert.equal(textOnly[0].answer?.optionId, undefined); assert.equal(textOnly[0].answer?.text, 'Custom answer');
console.log('Agent question event lifecycle tests passed');
