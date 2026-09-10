import type { AgentEvent } from '@/service/agent';
import { ChartType, LineType, OrderByRule, OrderByType } from '@/blocks/BI/Chart/constants';
import type { IChartItem } from '@/typings/dashboard';

const chartTypes = {
  Column: ChartType.Column, Bar: ChartType.Bar, Line: ChartType.Line, AreaLine: ChartType.AreaLine,
  Pie: ChartType.Pie, RingPie: ChartType.RingPie, RosePie: ChartType.RosePie, Funnel: ChartType.Funnel,
  Scatter: ChartType.Scatter, Statistics: ChartType.Statistics, Combo: ChartType.Combo,
};

export interface AgentChart {
  id: string;
  runId: string;
  resultId: string;
  chartType: keyof typeof chartTypes;
  title: string;
  xField?: string | null;
  yField?: string | null;
  series: { field: string; chartType: 'Column' | 'Line' | 'AreaLine' | 'Scatter'; axisPosition: 'left' | 'right' }[];
  data: Record<string, string | number | null>[];
  page?: { number: number; hasMore?: boolean | null } | null;
  warnings: string[];
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);

const isAgentChart = (value: unknown): value is AgentChart => {
  if (!isRecord(value) || typeof value.id !== 'string' || typeof value.runId !== 'string'
      || typeof value.resultId !== 'string' || typeof value.title !== 'string'
      || typeof value.chartType !== 'string' || !Object.hasOwn(chartTypes, value.chartType)
      || !(value.xField == null || typeof value.xField === 'string')
      || !(value.yField == null || typeof value.yField === 'string')) return false;
  return Array.isArray(value.data) && value.data.every((row) => isRecord(row)
    && Object.values(row).every((cell) => cell === null || typeof cell === 'string'
      || typeof cell === 'number' && Number.isFinite(cell)))
    && Array.isArray(value.series) && value.series.every((series) => isRecord(series)
      && typeof series.field === 'string' && ['Column', 'Line', 'AreaLine', 'Scatter'].includes(String(series.chartType))
      && ['left', 'right'].includes(String(series.axisPosition)))
    && (value.page == null || isRecord(value.page) && typeof value.page.number === 'number'
      && (value.page.hasMore == null || typeof value.page.hasMore === 'boolean'))
    && Array.isArray(value.warnings) && value.warnings.every((warning) => typeof warning === 'string');
};

export const updateAgentCharts = (current: AgentChart[], events: AgentEvent[]): AgentChart[] => {
  const additions = events.filter((event) => event.type === 'CHART_CREATED')
    .map((event) => ({ event, chart: event.payload.chart }))
    .filter((item): item is { event: AgentEvent; chart: AgentChart } =>
      isAgentChart(item.chart) && item.chart.runId === item.event.runId);
  if (!additions.length) return current;
  const charts = new Map(current.map((chart) => [chart.id, chart]));
  additions.forEach(({ chart }) => { if (!charts.has(chart.id)) charts.set(chart.id, chart); });
  return charts.size === current.length ? current : [...charts.values()];
};

export const isPartialChart = (chart: AgentChart) =>
  chart.page != null && (chart.page.number > 1 || chart.page.hasMore !== false);

export const agentChartDetail = (chart: AgentChart): IChartItem => ({
  chartSchema: {
    chartType: chartTypes[chart.chartType], title: chart.title,
    xField: chart.xField, yField: chart.yField,
    angleField: chart.xField, valueField: chart.yField,
    comboYAxisData: chart.series.map((series) => ({ ...series, chartType: chartTypes[series.chartType] })),
    data: chart.data, lineType: LineType.Straight, orderByType: OrderByType.DEFAULT,
    orderByRule: OrderByRule.DESC, themeColorCode: 'v1-baby-blue',
    chartOptionCheckbox: chart.chartType === 'Combo'
      ? ['showLegend', 'showAxisLine', 'showSplitLine', 'showSymbol']
      : ['showLegend', 'showLabel', 'showAxisLine', 'showSplitLine', 'showSymbol'],
  },
});
