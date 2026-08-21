import assert from 'node:assert/strict';
import { toKnowledgeSelectionReferences } from './knowledgeSelection';

const references = toKnowledgeSelectionReferences([
  {
    id: 186,
    type: 'KNOWLEDGE_TERM',
    key: '三全水饺',
    value: '客户端展示内容不能作为服务端知识上下文。',
  },
  {
    id: 220,
    type: 'BUSINESS_LOGIC',
    key: '销量',
    value: 'SUM(quantity)',
  },
]);

assert.deepEqual(references, [
  { id: 186, type: 'KNOWLEDGE_TERM' },
  { id: 220, type: 'BUSINESS_LOGIC' },
]);
assert.equal(Object.hasOwn(references[0], 'key'), false);
assert.equal(Object.hasOwn(references[0], 'value'), false);

console.log('Knowledge selection reference tests passed.');
