import assert from 'node:assert/strict';
import {
  detectMentionTrigger,
  reconcileSelectedMentions,
  replaceMentionTrigger,
  upsertSelectedMention,
  type SelectedMention,
} from './mentionSelection';

const tableMention = (name: string, tableType = 'TABLE'): SelectedMention => ({
  value: `table:${tableType}:${name}`,
  label: name,
  kind: 'table',
  tableName: name,
  tableType,
});

assert.deepEqual(detectMentionTrigger('@', 1), { query: '', start: 0, end: 1 });
assert.deepEqual(detectMentionTrigger('query @orders', 13), { query: 'orders', start: 6, end: 13 });
assert.equal(detectMentionTrigger('contact test@example.com', 24), null);
assert.equal(detectMentionTrigger('orders', 6), null);

assert.deepEqual(replaceMentionTrigger('query @ord', detectMentionTrigger('query @ord', 10)!, 'orders'), {
  value: 'query orders ',
  cursor: 13,
});

const orders = tableMention('orders');
const customers = tableMention('customers');
assert.deepEqual(upsertSelectedMention(upsertSelectedMention([], orders), customers), [orders, customers]);
assert.deepEqual(upsertSelectedMention([orders, customers], orders), [customers, orders]);
assert.deepEqual(reconcileSelectedMentions('query orders and customers', [orders, customers]), [orders, customers]);
assert.deepEqual(reconcileSelectedMentions('query customers', [orders, customers]), [customers]);
assert.deepEqual(reconcileSelectedMentions('query preorders', [orders]), []);
assert.deepEqual(reconcileSelectedMentions('query @orders', [orders]), [orders]);
