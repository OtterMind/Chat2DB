import assert from 'node:assert/strict';
import { splitSearchHighlight } from './highlightSearchText';

assert.deepEqual(splitSearchHighlight('orders[archive]', '['), [
  { text: 'orders', highlighted: false },
  { text: '[', highlighted: true },
  { text: 'archive]', highlighted: false },
]);
assert.deepEqual(splitSearchHighlight('schema.table', '.'), [
  { text: 'schema', highlighted: false },
  { text: '.', highlighted: true },
  { text: 'table', highlighted: false },
]);
assert.deepEqual(splitSearchHighlight('total*count', '*'), [
  { text: 'total', highlighted: false },
  { text: '*', highlighted: true },
  { text: 'count', highlighted: false },
]);
assert.deepEqual(splitSearchHighlight('<script>alert(1)</script>', '<script>'), [
  { text: '<script>', highlighted: true },
  { text: 'alert(1)</script>', highlighted: false },
]);
assert.deepEqual(splitSearchHighlight('OrdersORDERS', 'orders'), [
  { text: 'Orders', highlighted: true },
  { text: 'ORDERS', highlighted: true },
]);

console.log('Tree title highlight tests passed');
