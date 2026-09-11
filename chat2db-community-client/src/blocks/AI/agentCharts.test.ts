import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import type { AgentEvent } from '@/service/agent';
import { agentChartDetail, isPartialChart, updateAgentCharts } from './agentCharts';

const payload = {
  id: 'chart-1', runId: 'run-1', resultId: 'result-1', chartType: 'Line', title: 'Same title',
  xField: 'month', yField: 'value', series: [], warnings: [],
  data: [{ month: 'Jan', value: 0.1 }, { month: 'Feb', value: null }], page: { number: 1, hasMore: false },
};
const event = (chart: unknown, sequence = 1): AgentEvent => ({
  id: `event-${sequence}`, sessionId: 'session', runId: 'run-1', sequence,
  type: 'CHART_CREATED', payload: { chart }, occurredAt: '',
});
const first = updateAgentCharts([], [event(payload)]);
assert.equal(first.length, 1);
assert.strictEqual(updateAgentCharts(first, [event(payload)]), first);
const second = updateAgentCharts(first, [event({ ...payload, id: 'chart-2' }, 2)]);
assert.equal(second.length, 2, 'Equal titles do not merge distinct charts');
assert.deepEqual(agentChartDetail(first[0]).chartSchema?.data, payload.data);
assert.equal(agentChartDetail(first[0]).chartSchema?.data[1].value, null);
assert.equal(agentChartDetail({ ...first[0], chartType: 'Combo' }).chartSchema?.chartOptionCheckbox.includes('showLabel'), false);
assert.equal(isPartialChart(first[0]), false);
assert.equal(isPartialChart({ ...first[0], page: { number: 2, hasMore: false } }), true);
assert.equal(isPartialChart({ ...first[0], page: { number: 1, hasMore: null } }), true);
assert.deepEqual(updateAgentCharts([], [event({ ...payload, chartType: 'Bad' }), event({ ...payload, runId: 'foreign' })]), []);
assert.equal(updateAgentCharts([], [event({ ...payload, chartType: 'Statistics', xField: undefined, page: undefined })]).length, 1);
assert.equal(updateAgentCharts([], [event({ ...payload, data: [{ value: Number.NaN }] })]).length, 0);
for (const locale of ['zh-CN', 'en-US', 'es-ES', 'ja-JP', 'ko-KR']) {
  const source = readFileSync(new URL(`../../i18n/${locale}/stream.ts`, import.meta.url), 'utf8');
  for (const key of ['partialResult', 'viewQueryData', 'queryData']) assert.ok(source.includes(`stream.chart.${key}`));
}
console.log('Agent chart restoration, identity, numeric values and locale coverage passed.');
