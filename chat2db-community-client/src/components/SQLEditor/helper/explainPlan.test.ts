import assert from 'node:assert/strict';
import { displayMetric, parseExplainPlan } from './explainPlan';

const mysqlJsonPlan = JSON.stringify({
  query_block: {
    select_id: 1,
    cost_info: { query_cost: '4.70' },
    nested_loop: [
      {
        table: {
          table_name: 'obj002_orders',
          access_type: 'ref',
          possible_keys: ['idx_obj002_orders_user_id'],
          key: 'idx_obj002_orders_user_id',
          rows_examined_per_scan: 10,
          rows_produced_per_join: 10,
          filtered: '100.00',
          cost_info: { prefix_cost: '2.10' },
          attached_condition: 'obj002_orders.user_id = obj002_users.id',
        },
      },
      {
        table: {
          table_name: 'obj002_users',
          access_type: 'eq_ref',
          key: 'PRIMARY',
          rows_examined_per_scan: 1,
        },
      },
    ],
  },
});

const parsedJson = parseExplainPlan('json', mysqlJsonPlan);
assert.equal(parsedJson.parseError, undefined);
assert.equal(parsedJson.nodes[0].sourcePath, '$.query_block');
assert.equal(parsedJson.nodes[0].children[0].sourcePath, '$.query_block.nested_loop[0].table');
assert.equal(parsedJson.nodes[0].children[0].title, 'obj002_orders');
assert.equal(
  parsedJson.nodes[0].children[0].metrics.find((metric) => metric.label === 'Access')?.value,
  'ref',
);
assert.equal(
  parsedJson.nodes[0].children[0].metrics.find((metric) => metric.label === 'Estimated rows')?.value,
  '10',
);
assert.match(parsedJson.formattedRawText, /"query_block"/);

const analyzeText = [
  '-> Nested loop inner join  (cost=3.75 rows=10) (actual time=0.021..0.070 rows=10 loops=1)',
  '    -> Index lookup on obj002_orders using idx_obj002_orders_user_id  (cost=1.20 rows=10) (actual time=0.014..0.030 rows=10 loops=1)',
  '    -> Single-row index lookup on obj002_users using PRIMARY  (cost=0.25 rows=1) (actual time=0.003..0.003 rows=1 loops=10)',
].join('\n');
const parsedAnalyze = parseExplainPlan('analyze', analyzeText);
assert.equal(parsedAnalyze.nodes[0].sourcePath, 'line:1');
assert.equal(parsedAnalyze.nodes[0].children.length, 2);
assert.equal(parsedAnalyze.nodes[0].metrics.find((metric) => metric.label === 'Estimated rows')?.value, '10');
assert.equal(parsedAnalyze.nodes[0].metrics.find((metric) => metric.label === 'Actual rows')?.value, '10');
assert.equal(parsedAnalyze.nodes[0].metrics.find((metric) => metric.label === 'Loops')?.value, '1');
assert.equal(parsedAnalyze.nodes[0].metrics.find((metric) => metric.label === 'First row time')?.value, '0.021');
assert.equal(parsedAnalyze.nodes[0].metrics.find((metric) => metric.label === 'Total time')?.value, '0.070');

const invalidJson = parseExplainPlan('json', 'not json');
assert.equal(invalidJson.nodes[0].title, 'Unknown plan node');
assert.equal(invalidJson.nodes[0].sourcePath, '$');
assert.ok(invalidJson.parseError);

assert.equal(displayMetric(undefined), '-');
assert.equal(displayMetric([]), '-');
assert.equal(displayMetric(['idx_a', 'idx_b']), 'idx_a, idx_b');

console.log('explainPlan tests passed');
