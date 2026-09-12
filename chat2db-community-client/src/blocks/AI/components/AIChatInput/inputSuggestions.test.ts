import assert from 'node:assert/strict';
import { commandSuggestions, detectInputSuggestion, replaceSkillTrigger, skillSuggestions } from './inputSuggestions';

for (const text of ['/', '/sk', '/skill', '/skill:', '/skill:ch']) {
  const trigger = detectInputSuggestion(text, text.length, 'PI');
  assert.equal(trigger?.kind, 'slash');
  assert.deepEqual(skillSuggestions(['chart'], trigger!.query).map((item) => item.label), ['/skill:chart']);
  assert.equal(detectInputSuggestion(text, text.length, 'DEFAULT'), null);
  assert.equal(detectInputSuggestion(text, text.length), null);
}
assert.equal(detectInputSuggestion('/skill:chart question', 21, 'PI'), null);
assert.equal(detectInputSuggestion('explain /sk', 11, 'PI'), null);
assert.equal(detectInputSuggestion('/Users/dawn', 11, 'PI'), null);
assert.equal(detectInputSuggestion('https://example.com', 19, 'PI'), null);
assert.deepEqual(skillSuggestions(['chart', 'chart', 'query'], 'skill:q').map((item) => item.value), ['skill:query']);
assert.deepEqual(skillSuggestions(['chart'], 'missing'), []);
assert.deepEqual(commandSuggestions('mo').map((item) => item.label), ['/model']);
assert.deepEqual(commandSuggestions('').map((item) => item.label), [
  '/compact', '/copy', '/export', '/hotkeys', '/model', '/reload', '/session', '/settings', '/thinking', '/tree',
]);
const text = '  /skill:old 保留这个问题';
const trigger = detectInputSuggestion(text, 7, 'PI')!;
assert.deepEqual(replaceSkillTrigger(text, trigger, '/skill:chart'), { value: '  /skill:chart 保留这个问题', cursor: 15 });
assert.deepEqual(replaceSkillTrigger('/sk', detectInputSuggestion('/sk', 3, 'PI')!, '/skill:chart'), {
  value: '/skill:chart ', cursor: 13,
});
for (const runtime of ['DEFAULT', 'PI'] as const) {
  const mention = detectInputSuggestion('/skill:chart @ord', 17, runtime);
  assert.equal(mention?.kind, 'table');
  assert.equal(mention?.query, 'ord');
}
console.log('Skill completion prefix, runtime scope, caret replacement and table mentions passed');

const multiline = '/skill:old\n\n保留段落';
assert.deepEqual(replaceSkillTrigger(multiline, detectInputSuggestion(multiline, 5, 'PI')!, '/skill:chart'), {
  value: '/skill:chart\n\n保留段落', cursor: 14,
});
