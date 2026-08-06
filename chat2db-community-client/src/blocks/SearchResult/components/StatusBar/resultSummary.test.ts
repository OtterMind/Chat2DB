import assert from 'node:assert/strict';
import { formatResultSummary, type ResultSummaryTranslator } from './resultSummary';

const zhTranslation: ResultSummaryTranslator = (key, value) => {
  const translations = {
    'workspace.resultSet.cost': `耗时：${value}ms`,
    'workspace.resultSet.rows': `行数：${value}`,
    'common.text.executeDuration': `执行 ${value} ms`,
    'common.text.fetchDuration': `读取 ${value} ms`,
  };
  return translations[key];
};

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
  '耗时：6ms (执行 2ms · 读取 4ms) · 行数：5',
);

assert.equal(formatResultSummary({ dataList: [[], []] }, zhTranslation), '行数：2');
assert.equal(formatResultSummary({ dataList: [] }, zhTranslation), '行数：0');

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
  '耗时：0ms (执行 0ms · 读取 0ms) · 行数：0',
);

assert.equal(
  formatResultSummary(
    {
      dataList: [[]],
      executionMetrics: { executeDurationMs: 2, fetchDurationMs: 4, fetchedRowCount: 1 },
    },
    zhTranslation,
  ),
  '行数：1',
);

console.log('result status summary tests passed');
