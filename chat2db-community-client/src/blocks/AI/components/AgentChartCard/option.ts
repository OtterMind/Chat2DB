import type { BarSeriesOption, EChartsOption, LineSeriesOption, ScatterSeriesOption } from 'echarts';
import { CHART_COLORS } from '@/blocks/BI/Chart/constants';
import type { AgentChart } from '../../agentCharts';

type Cell = string | number | null;
type Metric = {
  field: string;
  chartType: 'Column' | 'Bar' | 'Line' | 'AreaLine' | 'Scatter';
  axisPosition: 'left' | 'right';
};
type Series = BarSeriesOption | LineSeriesOption | ScatterSeriesOption;

const numberValue = (value: Cell | undefined): number | null => {
  if (value == null || typeof value === 'string' && !value.trim()) return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
};

const displayValue = (value: Cell): string => {
  if (value === null) return 'NULL';
  if (typeof value === 'string' && (!value.trim() || value === 'NULL' || value === 'null')) {
    return JSON.stringify(value);
  }
  return String(value);
};

// ECharts selects legends by name, so distinct tuples must also have distinct display names.
const uniqueNames = (labels: string[]): string[] => {
  const used = new Set<string>();
  return labels.map((label) => {
    let name = label;
    let suffix = 2;
    while (used.has(name)) name = `${label} (${suffix++})`;
    used.add(name);
    return name;
  });
};

export const buildAgentChartOption = (
  chart: AgentChart,
  colors: { border: string; text: string },
): EChartsOption & { series: Series[] } => {
  const groupBy = chart.groupBy ?? [];
  const xField = chart.xField ?? '';
  const metrics: Metric[] = chart.chartType === 'Combo' ? chart.series : [{
    field: chart.yField ?? '',
    chartType: chart.chartType === 'Bar' ? 'Bar'
      : chart.chartType === 'Column' ? 'Column'
        : chart.chartType === 'Scatter' ? 'Scatter'
          : chart.chartType === 'AreaLine' ? 'AreaLine' : 'Line',
    axisPosition: 'left',
  }];
  const categories = new Map<string, Cell>();
  const groups = new Map<string, { values: Cell[]; rows: AgentChart['data'] }>();
  for (const row of chart.data) {
    const category = row[xField] ?? null;
    categories.set(JSON.stringify(category), category);
    const values = groupBy.map((field) => row[field] ?? null);
    const key = JSON.stringify(values);
    let group = groups.get(key);
    if (!group) {
      group = { values, rows: [] };
      groups.set(key, group);
    }
    group.rows.push(row);
  }
  const descriptors = [...groups].flatMap(([key, group]) => {
    const label = group.values.map((value, index) => `${groupBy[index]}=${displayValue(value)}`).join(' · ');
    const byCategory = new Map(group.rows.map((row) => [JSON.stringify(row[xField] ?? null), row]));
    return metrics.map((metric) => ({
      key, group, byCategory, metric,
      label: label ? (chart.chartType === 'Combo' ? `${metric.field} · ${label}` : label) : metric.field,
    }));
  });
  const names = uniqueNames(descriptors.map((descriptor) => descriptor.label));
  const scatter = chart.chartType === 'Scatter';
  const horizontal = chart.chartType === 'Bar';
  const individualBars = horizontal && !groupBy.length && !chart.stack;
  const series: Series[] = descriptors.map(({ key, group, byCategory, metric }, index) => {
    const base = {
      id: JSON.stringify([key, metric.field, metric.chartType, metric.axisPosition]),
      name: names[index],
      yAxisIndex: metric.axisPosition === 'right' ? 1 : 0,
      emphasis: { focus: 'series' as const },
    };
    if (scatter) {
      return {
        ...base, type: 'scatter',
        data: group.rows.map((row) => [numberValue(row[xField]), numberValue(row[metric.field])]),
      };
    }
    const data = individualBars
      ? group.rows.map((row) => numberValue(row[metric.field]))
      : [...categories.keys()].map((category) => numberValue(byCategory.get(category)?.[metric.field]));
    const stack = chart.stack && ['Column', 'Bar', 'AreaLine'].includes(metric.chartType)
      ? JSON.stringify([metric.chartType, metric.axisPosition, groupBy.length ? metric.field : null]) : undefined;
    if (metric.chartType === 'Column' || metric.chartType === 'Bar') {
      return { ...base, type: 'bar', data, stack };
    }
    if (metric.chartType === 'Scatter') return { ...base, type: 'scatter', data };
    return {
      ...base, type: 'line', data, stack, connectNulls: false, showSymbol: true,
      ...(metric.chartType === 'AreaLine' ? { areaStyle: {} } : {}),
    };
  });
  const categoryAxis = {
    type: 'category' as const,
    data: individualBars ? chart.data.map((row) => displayValue(row[xField] ?? null))
      : uniqueNames([...categories.values()].map(displayValue)),
    inverse: horizontal,
    axisLabel: { color: colors.text },
    axisLine: { lineStyle: { color: colors.border } },
  };
  const valueAxis = {
    type: 'value' as const,
    axisLabel: { color: colors.text },
    splitLine: { lineStyle: { color: colors.border } },
  };
  return {
    color: CHART_COLORS.find((palette) => palette.value === 'v1-colorful-1')?.colors,
    // Canvas text keeps query values and model-supplied labels out of HTML tooltips.
    tooltip: { trigger: scatter ? 'item' : 'axis', renderMode: 'richText', confine: true },
    legend: { type: 'scroll', top: 4, data: names, textStyle: { color: colors.text } },
    grid: { top: 42, left: 12, right: 16, bottom: 12, containLabel: true },
    xAxis: horizontal || scatter ? valueAxis : categoryAxis,
    yAxis: horizontal ? categoryAxis : metrics.some((metric) => metric.axisPosition === 'right')
      ? [valueAxis, { ...valueAxis, position: 'right', splitLine: { show: false } }] : valueAxis,
    series,
  };
};
