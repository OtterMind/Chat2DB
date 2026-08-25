export type TaskWorkspaceTabType =
  | 'BOARD'
  | 'ARCHIVE'
  | 'TASK_DETAIL'
  | 'TASK_CREATE'
  | 'SCHEDULES'
  | 'DATA_WIKI'
  | 'CONNECTORS'
  | 'AGENT_MANAGER'
  | 'AGENT_EDITOR';

export interface TaskWorkspaceTab {
  key: string;
  type: TaskWorkspaceTabType;
  title: string;
  entityId?: string;
  closable: boolean;
  dirty?: boolean;
}

export interface TaskWorkspaceRoute {
  type: TaskWorkspaceTabType;
  entityId?: string;
}

export const TASK_BOARD_TAB_KEY = 'board';

export function taskWorkspaceRouteForConnectorManagement(
  route: TaskWorkspaceRoute,
  connectorManagementEnabled: boolean,
): TaskWorkspaceRoute {
  return !connectorManagementEnabled && route.type === 'CONNECTORS' ? { type: 'BOARD' } : route;
}

export function taskWorkspaceTabsForConnectorManagement(
  tabs: TaskWorkspaceTab[],
  connectorManagementEnabled: boolean,
): TaskWorkspaceTab[] {
  if (connectorManagementEnabled || !tabs.some((tab) => tab.type === 'CONNECTORS')) return tabs;
  return tabs.filter((tab) => tab.type !== 'CONNECTORS');
}

export function parseTaskWorkspaceRoute(routePath: string): TaskWorkspaceRoute {
  const normalized = routePath.replace(/^#/, '').split('?')[0].replace(/\/$/, '') || '/tasks';
  const parts = normalized.split('/').filter(Boolean);
  if (parts[0] !== 'tasks') return { type: 'BOARD' };
  if (!parts[1]) return { type: 'BOARD' };
  if (parts[1] === 'archive') return { type: 'ARCHIVE' };
  if (parts[1] === 'new') return { type: 'TASK_CREATE' };
  if (parts[1] === 'schedules') {
    return { type: 'SCHEDULES', entityId: parts[2] && parts[2] !== 'new' ? decodeURIComponent(parts[2]) : undefined };
  }
  if (parts[1] === 'data-wikis') return { type: 'DATA_WIKI' };
  if (parts[1] === 'connectors') return { type: 'CONNECTORS' };
  if (parts[1] === 'agents') {
    if (!parts[2]) return { type: 'AGENT_MANAGER' };
    if (parts[2] === 'new') return { type: 'AGENT_EDITOR' };
    return { type: 'AGENT_EDITOR', entityId: decodeURIComponent(parts[2]) };
  }
  return { type: 'TASK_DETAIL', entityId: decodeURIComponent(parts[1]) };
}

export function taskWorkspaceTabKey(route: TaskWorkspaceRoute) {
  switch (route.type) {
    case 'BOARD': return TASK_BOARD_TAB_KEY;
    case 'ARCHIVE': return 'archive';
    case 'TASK_CREATE': return 'task:new';
    case 'TASK_DETAIL': return `task:${route.entityId}`;
    case 'SCHEDULES': return route.entityId ? `schedule:${route.entityId}` : 'schedules';
    case 'DATA_WIKI': return 'data-wikis';
    case 'CONNECTORS': return 'connectors';
    case 'AGENT_MANAGER': return 'agents';
    case 'AGENT_EDITOR': return route.entityId ? `agent:${route.entityId}` : 'agent:new';
    default: return TASK_BOARD_TAB_KEY;
  }
}

export function taskWorkspaceRoutePath(tab: Pick<TaskWorkspaceTab, 'type' | 'entityId'>) {
  switch (tab.type) {
    case 'BOARD': return '/tasks';
    case 'ARCHIVE': return '/tasks/archive';
    case 'TASK_CREATE': return '/tasks/new';
    case 'TASK_DETAIL': return `/tasks/${encodeURIComponent(tab.entityId || '')}`;
    case 'SCHEDULES': return tab.entityId
      ? `/tasks/schedules/${encodeURIComponent(tab.entityId)}`
      : '/tasks/schedules/new';
    case 'DATA_WIKI': return '/tasks/data-wikis';
    case 'CONNECTORS': return '/tasks/connectors';
    case 'AGENT_MANAGER': return '/tasks/agents';
    case 'AGENT_EDITOR': return tab.entityId
      ? `/tasks/agents/${encodeURIComponent(tab.entityId)}/edit`
      : '/tasks/agents/new';
    default: return '/tasks';
  }
}

export function upsertTaskWorkspaceTab(tabs: TaskWorkspaceTab[], next: TaskWorkspaceTab) {
  const existing = tabs.findIndex((tab) => tab.key === next.key);
  if (existing < 0) return [...tabs, next];
  const current = tabs[existing];
  const merged = { ...current, ...next, dirty: current.dirty ?? next.dirty };
  if (
    current.type === merged.type
    && current.title === merged.title
    && current.entityId === merged.entityId
    && current.closable === merged.closable
    && current.dirty === merged.dirty
  ) {
    return tabs;
  }
  return tabs.map((tab, index) => index === existing ? merged : tab);
}

export function nextTaskWorkspaceTabKey(tabs: TaskWorkspaceTab[], removedKey: string, activeKey: string) {
  if (removedKey !== activeKey) return activeKey;
  const index = tabs.findIndex((tab) => tab.key === removedKey);
  return tabs[index + 1]?.key || tabs[index - 1]?.key || TASK_BOARD_TAB_KEY;
}

export function shouldRefreshTaskDetail(
  activeTab: TaskWorkspaceTab | undefined,
  taskId?: string,
  activeRun = false,
  connectorAudit = false,
) {
  return Boolean((activeRun || connectorAudit)
    && taskId && activeTab?.type === 'TASK_DETAIL' && activeTab.entityId === taskId);
}
