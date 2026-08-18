export interface TaskScheduleCronValues {
  scheduleType: 'ONCE' | 'CRON';
  preset?: 'DAILY' | 'WEEKDAYS' | 'WEEKLY' | 'CUSTOM';
  time?: string;
  weekday?: number;
  cronExpression?: string;
}

export interface TaskScheduleRoute {
  open: boolean;
  createMode: boolean;
  scheduleId?: string;
}

interface DataScopeIdentity {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  tableNames?: string[];
}

export function cronFromScheduleValues(values: TaskScheduleCronValues): string | undefined {
  if (values.scheduleType !== 'CRON') return undefined;
  if (values.preset === 'CUSTOM') return values.cronExpression?.trim();
  const [hour = '09', minute = '00'] = (values.time || '09:00').split(':');
  if (values.preset === 'WEEKDAYS') return `${Number(minute)} ${Number(hour)} * * 1-5`;
  if (values.preset === 'WEEKLY') return `${Number(minute)} ${Number(hour)} * * ${values.weekday ?? 1}`;
  return `${Number(minute)} ${Number(hour)} * * *`;
}

export function canOpenScheduledTask(taskId?: string, linkState?: 'AVAILABLE' | 'ARCHIVED' | 'DELETED') {
  return Boolean(taskId) && (linkState === undefined || linkState === 'AVAILABLE');
}

export function sameDataScope(left: DataScopeIdentity, right: DataScopeIdentity) {
  return left.dataSourceId === right.dataSourceId
    && left.databaseName === right.databaseName
    && left.schemaName === right.schemaName
    && JSON.stringify(left.tableNames || []) === JSON.stringify(right.tableNames || []);
}

export function parseTaskScheduleRoute(routePath: string): TaskScheduleRoute {
  const path = routePath.replace(/^#/, '').split('?')[0];
  const segments = path.split('/').filter(Boolean);
  if (segments[0] !== 'tasks' || segments[1] !== 'schedules') {
    return { open: false, createMode: false };
  }
  const target = segments[2];
  if (!target || target === 'new') {
    return { open: true, createMode: true };
  }
  return { open: true, createMode: false, scheduleId: decodeURIComponent(target) };
}

export function taskScheduleRoutePath(scheduleId?: string) {
  return scheduleId
    ? `/tasks/schedules/${encodeURIComponent(scheduleId)}`
    : '/tasks/schedules/new';
}
