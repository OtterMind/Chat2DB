import assert from 'node:assert/strict';
import { formatResultSummary, type DurationTranslator } from './resultSummary';

const zhTranslation: DurationTranslator = (key, durationMs) =>
  key === 'common.text.executeDuration' ? `执行 ${durationMs} ms` : `读取 ${durationMs} ms`;

assert.equal(
  formatResultSummary(
    {
      dataList: [[], []],
      executionMetrics: {
        totalDurationMs: 6,
        executeDurationMs: 2,
        fetchDurationMs: 4,
        fetchedRowCount: 5,
      },
    },
    zhTranslation,
  ),
  'cost: 6ms (执行 2ms · 读取 4ms) rows: 5',
);

assert.equal(formatResultSummary({ dataList: [[], []] }, zhTranslation), 'rows: 2');
assert.equal(formatResultSummary({ dataList: [] }, zhTranslation), 'rows: 0');

assert.equal(
  formatResultSummary(
    {
      dataList: [],
      executionMetrics: {
        totalDurationMs: 0,
        executeDurationMs: 0,
        fetchDurationMs: 0,
        fetchedRowCount: 0,
      },
    },
    zhTranslation,
  ),
  'cost: 0ms (执行 0ms · 读取 0ms) rows: 0',
);

assert.equal(
  formatResultSummary(
    {
      dataList: [[]],
      executionMetrics: { executeDurationMs: 2, fetchDurationMs: 4, fetchedRowCount: 1 },
    },
    zhTranslation,
  ),
  'rows: 1',
);

console.log('result status summary tests passed');
