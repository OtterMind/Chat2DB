import assert from 'node:assert/strict';

import { buildUserMessageNavigationItems, getMessageNavigationRailWidth } from './messageNavigation';

const items = buildUserMessageNavigationItems(
  [
    { id: 'user-1', role: 'user', content: '  first\nquestion  ' },
    { id: 'assistant-1', role: 'assistant', content: '## first\nanswer' },
    { id: 'user-2', role: 'user', content: '继续' },
    { id: 'user-3', role: 'user', content: '继续' },
    { id: 'user-4', role: 'user', content: '   ' },
  ],
  'User message',
);

assert.deepEqual(items, [
  { id: 'user-1', index: 1, label: 'first question', assistantPreview: 'first answer' },
  { id: 'user-2', index: 2, label: '继续' },
  { id: 'user-3', index: 3, label: '继续' },
  { id: 'user-4', index: 4, label: 'User message' },
]);

assert.equal(new Set(items.map((item) => item.id)).size, items.length, 'navigation uses stable message ids');
assert.deepEqual(
  [0, 1, 2, 3, 4, 5].map((distance) => getMessageNavigationRailWidth(5 + distance, 5)),
  [30, 27, 19, 13, 9, 8],
  'rail width follows a bell curve and converges to the minimum',
);
assert.equal(getMessageNavigationRailWidth(3, -1), 8, 'unselected rails use the minimum width');

console.log('AI user message navigation tests passed');
