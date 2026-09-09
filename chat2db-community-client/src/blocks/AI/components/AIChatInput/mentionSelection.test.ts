import assert from 'node:assert/strict';
import './mentionTableRequestCoordinator.test';
import {
  detectMentionTrigger,
  filterUnselectedMentionCandidates,
  findNaturalFragmentRange,
  normalizeMentionInput,
  reconcileSelectedMentions,
  replaceMentionTrigger,
  upsertSelectedMention,
  type SelectedMention,
} from './mentionSelection';

const selected: SelectedMention[] = [
  {
    value: 'knowledge:KNOWLEDGE_TERM:1',
    label: '销量',
    kind: 'knowledge',
    knowledge: { id: 1, type: 'KNOWLEDGE_TERM', key: '销量', value: '商品销售数量' },
  },
  {
    value: 'knowledge:BUSINESS_LOGIC:2',
    label: '销量',
    kind: 'knowledge',
    knowledge: { id: 2, type: 'BUSINESS_LOGIC', key: '销量', value: 'SUM(quantity)' },
  },
];

assert.deepEqual(
  reconcileSelectedMentions('查询 @销量 ', selected).map(({ value }) => value),
  ['knowledge:KNOWLEDGE_TERM:1'],
);
assert.deepEqual(reconcileSelectedMentions('查询 @销量 和 @销量 ', selected), selected);
assert.equal(
  normalizeMentionInput('查询 @三全水饺 的 @销量 ', [
    {
      value: 'knowledge:KNOWLEDGE_TERM:3',
      label: '三全水饺',
      kind: 'knowledge',
      knowledge: { id: 3, type: 'KNOWLEDGE_TERM', key: '三全水饺', value: '三全食品旗下产品' },
    },
    selected[1],
  ]),
  '查询 三全水饺 的 销量 ',
);
assert.equal(normalizeMentionInput('联系 test@example.com', selected), '联系 test@example.com');

assert.deepEqual(detectMentionTrigger('我要查询三全', 6), {
  mode: 'natural',
  query: '我要查询三全',
  inputText: '我要查询三全',
  start: 0,
  end: 6,
});
const punctuatedNaturalInput = '请查看生产工。';
assert.deepEqual(detectMentionTrigger(punctuatedNaturalInput, punctuatedNaturalInput.length), {
  mode: 'natural',
  query: '请查看生产工',
  inputText: punctuatedNaturalInput,
  start: 0,
  end: 6,
});
assert.equal(detectMentionTrigger('华东 ', 3), null);
assert.deepEqual(detectMentionTrigger('我要查询 @三全', 8), {
  mode: 'explicit',
  query: '三全',
  inputText: '我要查询 @三全',
  start: 5,
  end: 8,
});
assert.equal(detectMentionTrigger('联系 test@example.com', 19)?.mode, 'natural');
assert.deepEqual(findNaturalFragmentRange('我要查询三全', 6, '三全水饺'), { start: 4, end: 6 });
assert.deepEqual(findNaturalFragmentRange('统计', 2, '动销商品统计'), { start: 0, end: 2 });
assert.deepEqual(replaceMentionTrigger('我要查询三全', 6, detectMentionTrigger('我要查询三全', 6)!, '三全水饺'), {
  value: '我要查询三全水饺',
  cursor: 8,
});
assert.deepEqual(replaceMentionTrigger('统计', 2, detectMentionTrigger('统计', 2)!, '动销商品统计'), {
  value: '动销商品统计',
  cursor: 6,
});
assert.deepEqual(
  replaceMentionTrigger(
    '我要查询三全水饺的销量',
    11,
    detectMentionTrigger('我要查询三全水饺的销量', 11)!,
    '三全水饺',
  ),
  {
    value: '我要查询三全水饺的销量',
    cursor: 11,
  },
);
assert.deepEqual(
  replaceMentionTrigger('我要查询未知内容', 8, detectMentionTrigger('我要查询未知内容', 8)!, '三全水饺'),
  {
    value: '我要查询未知内容',
    cursor: 8,
  },
);
assert.deepEqual(replaceMentionTrigger('我要查询 @三全', 8, detectMentionTrigger('我要查询 @三全', 8)!, '三全水饺'), {
  value: '我要查询 三全水饺 ',
  cursor: 10,
});
const punctuatedPrefixInput = '请查看生产工。';
assert.deepEqual(
  replaceMentionTrigger(
    punctuatedPrefixInput,
    punctuatedPrefixInput.length,
    detectMentionTrigger(punctuatedPrefixInput, punctuatedPrefixInput.length)!,
    '生产工单',
  ),
  {
    value: '请查看生产工单。',
    cursor: 7,
  },
);

const knowledgeTerm: SelectedMention = {
  value: 'knowledge:KNOWLEDGE_TERM:3',
  label: '三全水饺',
  kind: 'knowledge',
  knowledge: { id: 3, type: 'KNOWLEDGE_TERM', key: '三全水饺', value: '三全食品旗下产品' },
};
const businessLogic: SelectedMention = {
  value: 'knowledge:BUSINESS_LOGIC:2',
  label: '销量',
  kind: 'knowledge',
  knowledge: { id: 2, type: 'BUSINESS_LOGIC', key: '销量', value: 'SUM(quantity)' },
};
assert.deepEqual(upsertSelectedMention(upsertSelectedMention([], knowledgeTerm), businessLogic), [
  knowledgeTerm,
  businessLogic,
]);
assert.deepEqual(upsertSelectedMention([knowledgeTerm, businessLogic], knowledgeTerm), [businessLogic, knowledgeTerm]);

const selectedContribution: SelectedMention = {
  value: 'knowledge:BUSINESS_LOGIC:225',
  label: '重点客户贡献率',
  kind: 'knowledge',
  knowledge: { id: 225, type: 'BUSINESS_LOGIC', key: '重点客户贡献率', value: '重点客户销售额占比' },
};
assert.deepEqual(
  filterUnselectedMentionCandidates(
    [
      { value: 'knowledge:BUSINESS_LOGIC:225', label: '重点客户贡献率' },
      { value: 'knowledge:SQL_TEMPLATE:262', label: '重点客户贡献率' },
      { value: 'knowledge:KNOWLEDGE_TERM:209', label: '华东大区' },
    ],
    [selectedContribution],
  ),
  [{ value: 'knowledge:KNOWLEDGE_TERM:209', label: '华东大区' }],
);
