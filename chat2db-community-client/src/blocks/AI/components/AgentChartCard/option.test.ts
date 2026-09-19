import assert from 'node:assert/strict';
import { init } from 'echarts';
import type { AgentChart } from '../../agentCharts';
import { buildAgentChartOption } from './option';

const colors = { border: '#444444', text: '#eeeeee' };
const chart: AgentChart = {
  id: 'chart', runId: 'run', resultId: 'result', chartType: 'Column', title: '按月、地区支付额',
  xField: 'month', yField: 'amount', groupBy: ['region'], series: [], warnings: [],
  data: [
    { month: 'Mar', region: '华东', amount: 4, count: 2 },
    { month: 'Jan', region: '华东', amount: 2, count: 1 },
    { month: 'Mar', region: '华西', amount: 6, count: 3 },
    { month: 'Feb', region: '华西', amount: 0, count: 0 },
  ],
};

for (const chartType of ['Column', 'Bar', 'Line', 'AreaLine'] as const) {
  for (const stack of chartType === 'Line' ? [false] : [false, true]) {
    const option = buildAgentChartOption({ ...chart, chartType, stack }, colors);
    const axis = chartType === 'Bar' ? option.yAxis : option.xAxis;
    assert.ok(axis && !Array.isArray(axis) && 'data' in axis);
    assert.deepEqual(axis.data, ['Mar', 'Jan', 'Feb'], 'Keep the SQL result ordering across groups');
    assert.deepEqual(option.series.map((series) => series.data), [[4, 2, null], [6, null, 0]]);
    assert.deepEqual(option.series.map((series) => series.name), ['region=华东', 'region=华西']);
    assert.ok(Array.isArray(option.color) && new Set(option.color).size > 5, 'Groups get distinct palette colors');
    assert.equal(option.series[0].type, ['Column', 'Bar'].includes(chartType) ? 'bar' : 'line');
    if (option.series[0].type === 'line') {
      assert.equal(option.series[0].connectNulls, false, 'A missing group must leave a real gap');
      assert.equal(Boolean(option.series[0].areaStyle), chartType === 'AreaLine');
    }
    if (option.series[0].type !== 'scatter' && option.series[1].type !== 'scatter') {
      assert.equal(Boolean(option.series[0].stack), stack);
      assert.equal(option.series[0].stack, option.series[1].stack);
    }
    const instance = init(null, undefined, { renderer: 'svg', ssr: true, width: 720, height: 340 });
    instance.setOption(option);
    assert.match(instance.renderToSVGString(), /<svg/);
    instance.dispose();
  }
}

const combo = buildAgentChartOption({
  ...chart, chartType: 'Combo', stack: true,
  series: [
    { field: 'amount', chartType: 'Column', axisPosition: 'left' },
    { field: 'count', chartType: 'AreaLine', axisPosition: 'right' },
  ],
}, colors);
assert.deepEqual(combo.series.map((series) => series.yAxisIndex), [0, 1, 0, 1]);
assert.deepEqual(combo.series.map((series) => series.data), [[4, 2, null], [2, 1, null], [6, null, 0], [3, null, 0]]);
assert.equal(combo.series[0].type, 'bar');
assert.equal(combo.series[1].type, 'line');
if (combo.series[0].type === 'bar' && combo.series[1].type === 'line'
  && combo.series[2].type === 'bar' && combo.series[3].type === 'line') {
  assert.equal(combo.series[0].stack, combo.series[2].stack);
  assert.equal(combo.series[1].stack, combo.series[3].stack);
  assert.notEqual(combo.series[0].stack, combo.series[1].stack, 'Do not stack different axes/types');
}
const metricStacks = buildAgentChartOption({
  ...chart, chartType: 'Combo', stack: true,
  series: ['amount', 'count'].map((field) => ({ field, chartType: 'Column', axisPosition: 'left' })),
}, colors);
if (metricStacks.series[0].type === 'bar' && metricStacks.series[1].type === 'bar') {
  assert.notEqual(metricStacks.series[0].stack, metricStacks.series[1].stack, 'Group stacks keep metrics separate');
}
const wideStacks = buildAgentChartOption({
  ...chart, chartType: 'Combo', groupBy: [], stack: true, data: chart.data.slice(0, 2),
  series: ['amount', 'count'].map((field) => ({ field, chartType: 'Column', axisPosition: 'left' })),
}, colors);
if (wideStacks.series[0].type === 'bar' && wideStacks.series[1].type === 'bar') {
  assert.equal(wideStacks.series[0].stack, wideStacks.series[1].stack, 'Explicit wide-form metrics can stack');
}

const scatter = buildAgentChartOption({
  ...chart, chartType: 'Scatter', xField: 'x',
  data: [
    { x: 1, region: 'A', amount: 2 }, { x: 1, region: 'A', amount: 3 },
    { x: 4, region: 'B', amount: null }, { x: 5, region: 'B', amount: 0 },
  ],
}, colors);
assert.deepEqual(scatter.series.map((series) => series.data), [[[1, 2], [1, 3]], [[4, null], [5, 0]]],
  'Scatter preserves every point, including repeated X values');
assert.ok(scatter.xAxis && !Array.isArray(scatter.xAxis));
assert.equal(scatter.xAxis.type, 'value');

const collisions = buildAgentChartOption({
  ...chart, groupBy: ['a', 'b'],
  data: [
    { month: 'Jan', a: 'x · b=y', b: 'z', amount: 1 },
    { month: 'Jan', a: 'x', b: 'y · b=z', amount: 2 },
    { month: 'Jan', a: null, b: '', amount: 3 },
    { month: 'Jan', a: 'NULL', b: '', amount: 4 },
    { month: 'Jan', a: 'null', b: '', amount: 5 },
    { month: 'Jan', a: '', b: '', amount: 6 },
  ],
}, colors);
assert.equal(new Set(collisions.series.map((series) => series.id)).size, 6);
assert.equal(new Set(collisions.series.map((series) => series.name)).size, 6, 'Legend collisions must not merge groups');
assert.deepEqual(collisions.series.map((series) => series.data), [[1], [2], [3], [4], [5], [6]]);

const categories = buildAgentChartOption({
  ...chart,
  data: [null, '', 'NULL', 'null', 1, '1'].map((month, index) => ({ month, region: 'A', amount: index })),
}, colors);
assert.ok(categories.xAxis && !Array.isArray(categories.xAxis) && 'data' in categories.xAxis);
assert.equal(new Set(categories.xAxis.data).size, 6, 'Category labels also keep scalar identities separate');
assert.deepEqual(categories.series[0].data, [0, 1, 2, 3, 4, 5]);

const untrusted = buildAgentChartOption({
  ...chart, data: [{ month: '<script>alert(1)</script>', region: '<img src=x onerror=alert(1)>', amount: 8 }],
}, colors);
assert.ok(untrusted.tooltip && !Array.isArray(untrusted.tooltip));
assert.equal(untrusted.tooltip.renderMode, 'richText', 'Tooltips never interpret query values as HTML');
const instance = init(null, undefined, { renderer: 'svg', ssr: true, width: 720, height: 340 });
instance.setOption(untrusted);
const svg = instance.renderToSVGString();
assert.ok(!svg.includes('<img src=x') && !svg.includes('<script>'));
assert.ok(svg.includes('&lt;img') && svg.includes('&lt;script&gt;'));
instance.dispose();
console.log('V2 grouped/stacked chart matrix, SQL ordering, gaps, tuple identity, dual axes and SVG safety passed.');

// The tool contract always uses xField for categories and yField for metrics,
// including a plain horizontal Bar. Repeated categories must keep every row.
const plainBar: AgentChart = {
  ...chart, chartType: 'Bar', groupBy: [], stack: false, xField: 'category', yField: 'rate',
  data: [
    { category: 'Alpha', rate: 50 }, { category: 'Beta', rate: 66.67 },
    { category: 'Beta', rate: 0 }, { category: 'Gamma', rate: null },
    { category: 'Delta', rate: -5 },
  ],
};
const plainOption = buildAgentChartOption(plainBar, colors);
assert.ok(plainOption.yAxis && !Array.isArray(plainOption.yAxis) && 'data' in plainOption.yAxis);
assert.deepEqual(plainOption.yAxis.data, ['Alpha', 'Beta', 'Beta', 'Gamma', 'Delta']);
assert.equal(plainOption.yAxis.inverse, true, 'Horizontal rankings keep SQL row order from top to bottom');
assert.ok(plainOption.xAxis && !Array.isArray(plainOption.xAxis));
assert.equal(plainOption.xAxis.type, 'value');
assert.equal(plainOption.series[0].name, 'rate', 'Legend names the numeric metric');
assert.deepEqual(plainOption.series[0].data, [50, 66.67, 0, null, -5]);
const plainInstance = init(null, undefined, { renderer: 'svg', ssr: true, width: 720, height: 340 });
plainInstance.setOption({ ...plainOption, animation: false });
const plainSvg = plainInstance.renderToSVGString();
assert.match(plainSvg, />Alpha<\/text>/);
assert.match(plainSvg, />rate<\/text>/);
assert.ok(plainSvg.includes('<path'), 'Produces actual SVG graphics');
plainInstance.dispose();
console.log('Plain V2 Bar: category/value axes, metric legend, repeated rows, zero, null and negatives passed.');
