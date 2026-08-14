import type {
  AgentArtifactDetail,
  AgentArtifactVersion,
  AgentRunEvent,
  AgentTask,
  AgentTaskStatus,
} from '@/service/agent';

const DSML_TOOL_BLOCK = /<｜｜DSML｜｜tool_calls>[\s\S]*?<\/｜｜DSML｜｜tool_calls>/gi;
const XML_TOOL_BLOCK = /<tool_calls?>[\s\S]*?<\/tool_calls?>/gi;
const JSON_CHART_BLOCK = /```chart\s*([\s\S]*?)```/gi;
const CHART_SECTION_LABEL = /^(?:图表展示|charts?)\s*[:：]?$/i;
const PIE_START = /^pie(?:\s+title\s+(.+))?$/i;
const PIE_ROW = /^["'](.+?)["']\s*:\s*(-?\d+(?:\.\d+)?)$/;
const XY_START = /^xychart-beta$/i;
const XY_TITLE = /^title\s+["']?(.*?)["']?$/i;
const XY_AXIS = /^x-axis\s*\[(.*)]$/i;
const XY_SERIES = /^(bar|line)\s*\[(.*)]$/i;

export interface AgentChartPresentation {
  markdown: string;
  charts: Array<Record<string, unknown>>;
}

function chartSpec(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const chart = value as Record<string, unknown>;
  return typeof chart.chartType === 'string' && Array.isArray(chart.data) && chart.data.length
    ? chart
    : undefined;
}

function splitChartValues(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim().replace(/^["']|["']$/g, ''))
    .filter(Boolean);
}

/**
 * Converts the lightweight Mermaid chart syntax commonly emitted by models into
 * the same chart schema used by Dashboard. Parsed source lines are removed from
 * Markdown so historical task results do not display chart source as text.
 */
export function extractAgentChartPresentation(value?: string): AgentChartPresentation {
  const charts: Array<Record<string, unknown>> = [];
  const withoutJsonCharts = (value || '').replace(JSON_CHART_BLOCK, (_, body: string) => {
    try {
      const parsed = chartSpec(JSON.parse(body));
      if (parsed) {
        charts.push(parsed);
        return '';
      }
    } catch {
      // Preserve malformed model output so the user can still inspect it.
    }
    return `\`\`\`chart${body}\`\`\``;
  });
  const lines = withoutJsonCharts.split('\n');
  const consumed = new Set<number>();

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index].trim();
    const pie = PIE_START.exec(line);
    if (pie) {
      const data: Array<{ category: string; value: number }> = [];
      let cursor = index + 1;
      while (cursor < lines.length) {
        const row = PIE_ROW.exec(lines[cursor].trim());
        if (!row) break;
        data.push({ category: row[1], value: Number(row[2]) });
        cursor += 1;
      }
      if (data.length && data.length <= 500) {
        charts.push({
          chartType: 'Pie',
          angleField: 'category',
          valueField: 'value',
          title: pie[1]?.trim().replace(/^["']|["']$/g, ''),
          data,
        });
        for (let row = index; row < cursor; row += 1) consumed.add(row);
        if (index > 0 && CHART_SECTION_LABEL.test(lines[index - 1].trim())) consumed.add(index - 1);
        index = cursor - 1;
        continue;
      }
    }

    if (!XY_START.test(line)) continue;
    let cursor = index + 1;
    let title: string | undefined;
    let categories: string[] = [];
    let seriesType: string | undefined;
    let values: number[] = [];
    while (cursor < lines.length) {
      const current = lines[cursor].trim();
      const titleMatch = XY_TITLE.exec(current);
      const axisMatch = XY_AXIS.exec(current);
      const seriesMatch = XY_SERIES.exec(current);
      if (titleMatch) title = titleMatch[1].trim().replace(/^["']|["']$/g, '');
      else if (axisMatch) categories = splitChartValues(axisMatch[1]);
      else if (/^y-axis\b/i.test(current)) {
        // The Dashboard schema derives its numeric axis from the series data.
      } else if (seriesMatch) {
        seriesType = seriesMatch[1].toLowerCase();
        values = splitChartValues(seriesMatch[2]).map(Number);
      } else if (current && current !== '```') break;
      cursor += 1;
    }
    if (categories.length
      && categories.length <= 500
      && categories.length === values.length
      && values.every(Number.isFinite)) {
      charts.push({
        chartType: seriesType === 'line' ? 'Line' : 'Column',
        xField: 'category',
        yField: 'value',
        title,
        data: categories.map((category, row) => ({ category, value: values[row] })),
      });
      for (let row = index; row < cursor; row += 1) consumed.add(row);
      if (index > 0 && CHART_SECTION_LABEL.test(lines[index - 1].trim())) consumed.add(index - 1);
      index = cursor - 1;
    }
  }

  return {
    markdown: lines
      .filter((_, index) => !consumed.has(index))
      .join('\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim(),
    charts,
  };
}

export function cleanAgentMarkdown(value?: string): string {
  return (value || '')
    .replace(DSML_TOOL_BLOCK, '')
    .replace(XML_TOOL_BLOCK, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

export interface AgentToolActivity {
  id: string;
  name: string;
  arguments?: string;
  result?: string;
  occurredAt: string | number;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
}

export function buildToolActivities(events: AgentRunEvent[]): AgentToolActivity[] {
  const rows: AgentToolActivity[] = [];
  const byCallId = new Map<string, AgentToolActivity>();
  events.forEach((event) => {
    if (event.type !== 'TOOL_CALL' && event.type !== 'TOOL_RESULT') return;
    const payloadCallId = event.payload?.toolCallId;
    const callId = String(payloadCallId || event.eventId);
    if (event.type === 'TOOL_CALL') {
      const row: AgentToolActivity = {
        id: callId,
        name: String(event.payload?.name || event.content || 'tool'),
        arguments: typeof event.payload?.arguments === 'string' ? event.payload.arguments : undefined,
        occurredAt: event.occurredAt,
        status: 'RUNNING',
      };
      rows.push(row);
      byCallId.set(callId, row);
      return;
    }
    const resultName = String(event.payload?.name || 'tool');
    const existing = byCallId.get(callId)
      || [...rows].reverse().find((row) => row.status === 'RUNNING' && row.name === resultName);
    if (existing) {
      existing.result = event.content;
      existing.status = event.payload?.success === false || event.payload?.status === 'FAILED'
        ? 'FAILED'
        : 'COMPLETED';
    } else {
      rows.push({
        id: callId,
        name: String(event.payload?.name || 'tool'),
        result: event.content,
        occurredAt: event.occurredAt,
        status: event.payload?.success === false || event.payload?.status === 'FAILED'
          ? 'FAILED'
          : 'COMPLETED',
      });
    }
  });
  return rows;
}

export const TASK_BOARD_COLUMNS: Array<{
  key: string;
  statuses: AgentTaskStatus[];
}> = [
  { key: 'backlog', statuses: ['BACKLOG', 'TODO'] },
  { key: 'active', statuses: ['IN_PROGRESS', 'BLOCKED'] },
  { key: 'review', statuses: ['IN_REVIEW'] },
  { key: 'complete', statuses: ['DONE', 'CANCELLED'] },
];

export const TASK_TRANSITIONS: Record<AgentTaskStatus, AgentTaskStatus[]> = {
  BACKLOG: ['TODO', 'CANCELLED'],
  TODO: ['IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['IN_REVIEW', 'BLOCKED', 'CANCELLED'],
  IN_REVIEW: ['IN_PROGRESS', 'DONE', 'BLOCKED', 'CANCELLED'],
  BLOCKED: ['IN_PROGRESS', 'CANCELLED'],
  DONE: [],
  CANCELLED: [],
};

export function groupTasks(tasks: AgentTask[]) {
  return TASK_BOARD_COLUMNS.map((column) => ({
    ...column,
    tasks: tasks.filter((task) => column.statuses.includes(task.status)),
  }));
}

export function currentArtifactVersion(detail: AgentArtifactDetail): AgentArtifactVersion | undefined {
  return detail.versions.find((version) => version.version === detail.artifact.currentVersion);
}

export function artifactCharts(detail: AgentArtifactDetail): Array<Record<string, unknown>> {
  const charts = currentArtifactVersion(detail)?.content?.charts;
  return Array.isArray(charts) ? (charts as Array<Record<string, unknown>>) : [];
}

export function artifactTables(detail: AgentArtifactDetail): Array<{
  title?: string;
  columns: string[];
  rows: Array<Record<string, unknown>>;
}> {
  const tables = currentArtifactVersion(detail)?.content?.tables;
  return Array.isArray(tables)
    ? (tables as Array<{ title?: string; columns: string[]; rows: Array<Record<string, unknown>> }>)
    : [];
}

export function artifactMarkdown(detail: AgentArtifactDetail): string {
  const blocks = currentArtifactVersion(detail)?.content?.blocks;
  if (!Array.isArray(blocks)) {
    return '';
  }
  return blocks
    .filter((block): block is { type: string; content: string } => {
      if (!block || typeof block !== 'object') {
        return false;
      }
      const value = block as Record<string, unknown>;
      return value.type === 'MARKDOWN' && typeof value.content === 'string';
    })
    .map((block) => cleanAgentMarkdown(block.content))
    .filter(Boolean)
    .join('\n\n');
}
