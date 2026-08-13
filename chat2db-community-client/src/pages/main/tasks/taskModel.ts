import type {
  AgentArtifactDetail,
  AgentArtifactVersion,
  AgentRunEvent,
  AgentTask,
  AgentTaskStatus,
} from '@/service/agent';

const DSML_TOOL_BLOCK = /<｜｜DSML｜｜tool_calls>[\s\S]*?<\/｜｜DSML｜｜tool_calls>/gi;
const XML_TOOL_BLOCK = /<tool_calls?>[\s\S]*?<\/tool_calls?>/gi;

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
  status: 'RUNNING' | 'COMPLETED';
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
      existing.status = 'COMPLETED';
    } else {
      rows.push({
        id: callId,
        name: String(event.payload?.name || 'tool'),
        result: event.content,
        occurredAt: event.occurredAt,
        status: 'COMPLETED',
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
