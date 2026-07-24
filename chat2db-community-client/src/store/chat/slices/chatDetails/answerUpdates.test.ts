import assert from 'node:assert/strict';
import type { AnswerVO } from '@/typings/chat';
import { updateFirstAnswer } from './answerUpdates';

const first = { id: 1, content: 'first' } as AnswerVO;
const second = { id: 2, content: 'second' } as AnswerVO;
const answers = [first, second];

const updated = updateFirstAnswer(answers, { content: 'updated' });

assert.equal(updated?.length, 2);
assert.deepEqual(updated?.[0], { ...first, content: 'updated' });
assert.equal(updated?.[1], second);
assert.deepEqual(answers, [first, second]);
assert.equal(updateFirstAnswer(undefined, { content: 'ignored' }), undefined);

console.log('Chat answer update tests passed');
