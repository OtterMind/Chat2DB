import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import type { AgentEvent } from '@/service/agent';
import { agentChartDetail, isPartialChart, updateAgentCharts, usesGroupedAgentChart } from './agentCharts';
import { buildAgentChartOption } from './components/AgentChartCard/option';

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
assert.equal(usesGroupedAgentChart(first[0]), false, 'Historic charts keep the existing renderer');
assert.equal(updateAgentCharts([], [event({ ...payload, groupBy: null, stack: null })]).length, 1);
const groupedPayload = {
  ...payload, chartType: 'Column', groupBy: ['region'], stack: true,
  data: [{ month: 'Jan', region: '华东', value: 10 }, { month: 'Jan', region: '华西', value: 20 }],
};
const restored = updateAgentCharts([], JSON.parse(JSON.stringify([event(groupedPayload)])));
assert.equal(restored.length, 1);
assert.deepEqual(restored[0].groupBy, ['region']);
assert.equal(restored[0].stack, true);
assert.equal(usesGroupedAgentChart(restored[0]), true);
assert.deepEqual(buildAgentChartOption(restored[0], { border: '#ddd', text: '#111' })
  .series.map((series) => series.data), [[10], [20]], 'Restored event payloads render the same groups');
for (const invalid of [
  { groupBy: 'region' }, { groupBy: ['region', 'region'] }, { groupBy: [''] }, { groupBy: [null] },
  { groupBy: ['a', 'b', 'c', 'd'] }, { groupBy: ['month'] }, { groupBy: ['value'] }, { groupBy: ['missing'] },
  { stack: 'true' }, { stack: 1 }, { chartType: 'Pie' }, { chartType: 'Statistics' },
  { chartType: 'Line' }, { chartType: 'Scatter' },
  { data: [...groupedPayload.data, groupedPayload.data[0]] },
]) {
  assert.equal(updateAgentCharts([], [event({ ...groupedPayload, ...invalid })]).length, 0,
    `Reject invalid group/stack payload ${JSON.stringify(invalid)}`);
}
for (const chartType of ['Line', 'Scatter', 'AreaLine', 'Bar']) {
  assert.equal(updateAgentCharts([], [event({ ...groupedPayload, chartType, stack: false })]).length, 1);
}
assert.equal(updateAgentCharts([], [event({
  ...groupedPayload, chartType: 'Scatter', stack: false,
  data: [...groupedPayload.data, { ...groupedPayload.data[0], value: 15 }],
})]).length, 1, 'Scatter allows repeated X values for the same group');
const seriesLimit = (count: number) => ({
  ...groupedPayload,
  data: Array.from({ length: count }, (_, index) => ({ month: 'Jan', region: index, value: 1 })),
});
assert.equal(updateAgentCharts([], [event(seriesLimit(32))]).length, 1);
assert.equal(updateAgentCharts([], [event(seriesLimit(33))]).length, 0);
const comboPayload = {
  ...groupedPayload, chartType: 'Combo',
  series: [
    { field: 'value', chartType: 'Column', axisPosition: 'left' },
    { field: 'count', chartType: 'Line', axisPosition: 'right' },
  ],
};
assert.equal(updateAgentCharts([], [event({ ...comboPayload, data: seriesLimit(16).data })]).length, 1);
assert.equal(updateAgentCharts([], [event({ ...comboPayload, data: seriesLimit(17).data })]).length, 0);
assert.equal(updateAgentCharts([], [event({
  ...comboPayload, groupBy: [], data: [groupedPayload.data[0]],
})]).length, 1);
assert.equal(updateAgentCharts([], [event({
  ...comboPayload, series: [comboPayload.series[0], comboPayload.series[0]],
})]).length, 0, 'Duplicate metric fields cannot create duplicate ECharts series IDs');
assert.equal(updateAgentCharts([], [event({
  ...comboPayload, series: [{ field: 'value', chartType: 'Line', axisPosition: 'left' }],
})]).length, 0, 'Stacked Combo must contain a column or area series');
for (const locale of ['zh-CN', 'en-US', 'es-ES', 'ja-JP', 'ko-KR']) {
  const source = readFileSync(new URL(`../../i18n/${locale}/stream.ts`, import.meta.url), 'utf8');
  for (const key of ['partialResult', 'viewQueryData', 'queryData']) assert.ok(source.includes(`stream.chart.${key}`));
}
console.log('Agent chart restoration, group/stack schema, identity, series limits and locale coverage passed.');
