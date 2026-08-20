import ChartCardBox from '@/blocks/BI/ChartCardBox';
import { ChartType, LineType, OrderByRule, OrderByType } from '@/blocks/BI/Chart/constants';
import type { ChartSchema } from '@/blocks/BI/Chart/typings';
import { TableDataType } from '@/constants/table';
import i18n from '@/i18n';
import { listAIModelConfigs } from '@/service/aiModelConfig';
import aiStreamService from '@/service/aiStream';
import { setPendingConversationTarget } from '@/utils/conversationNavigation';
import agentService, {
  type AgentApproval,
  type AgentArtifactContentMode,
  type AgentArtifactDetail,
  type AgentDefinition,
  type AgentRun,
  type AgentTask,
  type AgentTaskContextType,
  type AgentTaskDetail,
  type AgentTaskStatus,
} from '@/service/agent';
import connectionService from '@/service/connection';
import { getDashboardList } from '@/service/dashboard';
import type { IChartItem, IConnectionDetails, IDashboardItem } from '@/typings';
import feedback from '@/utils/feedback';
import CustomTabs from '@/components/Tabs';
import { useGlobalStore } from '@/store/global';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Empty,
  Form,
  Input,
  Mentions,
  Modal,
  Segmented,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
  Timeline,
  Tooltip,
} from 'antd';
import dayjs from 'dayjs';
import {
  ArrowLeft,
  Archive,
  Bot,
  CalendarClock,
  ChevronRight,
  CircleCheck,
  CircleDot,
  CircleEllipsis,
  CircleHelp,
  CirclePause,
  ExternalLink,
  FileBarChart,
  Flag,
  FolderKanban,
  ListChecks,
  MessageSquareText,
  Pin,
  Play,
  Plus,
  RefreshCw,
  Rows3,
  Search,
  Send,
  ShieldCheck,
  TerminalSquare,
  Trash2,
  X,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import {
  artifactCharts,
  artifactMarkdown,
  artifactTables,
  buildToolActivities,
  cleanAgentMarkdown,
  currentArtifactVersion,
  extractAgentChartPresentation,
  groupTasks,
  taskPriorityLevel,
  TASK_BOARD_COLUMNS,
  TASK_TRANSITIONS,
  type TaskBoardColumnKey,
  upsertTask,
} from './taskModel';
import {
  AGENT_TASK_CREATED_EVENT,
  cacheAgentTaskDetail,
  getCachedAgentTaskDetail,
} from './taskNavigation';
import { useStyles } from './style';
import AgentManagerPage from './AgentManagerPage';
import ApprovalModeTag from './ApprovalModeTag';
import TaskCreatePage from './TaskCreatePage';
import TaskSchedulePage from './TaskSchedulePage';
import { AgentAvatar, AgentIdentity, RunStatusMark, RuntimeBadge } from './TaskPrimitives';
import { parseTaskScheduleRoute, taskScheduleRoutePath } from './taskScheduleModel';
import { dataSourceDisplayName } from './taskDataSource';
import { filterTasks } from './taskFilters';
import {
  nextTaskWorkspaceTabKey,
  parseTaskWorkspaceRoute,
  shouldRefreshTaskDetail,
  taskWorkspaceRoutePath,
  taskWorkspaceTabKey,
  upsertTaskWorkspaceTab,
  type TaskWorkspaceRoute,
  type TaskWorkspaceTab,
} from './taskWorkspaceModel';

type ViewMode = 'board' | 'list';

const statusColor: Record<AgentTaskStatus, string> = {
  BACKLOG: 'default',
  TODO: 'blue',
  IN_PROGRESS: 'processing',
  WAITING_APPROVAL: 'warning',
  IN_REVIEW: 'purple',
  BLOCKED: 'error',
  DONE: 'success',
  CANCELLED: 'default',
};

const boardColumnIcon = {
  backlog: CircleEllipsis,
  active: CircleDot,
  approval: CircleHelp,
  review: CirclePause,
  complete: CircleCheck,
};

function priorityLabel(priority?: number) {
  return i18n(`task.priority.${taskPriorityLevel(priority)}` as Parameters<typeof i18n>[0]);
}

function statusLabel(status: string) {
  const key = `task.status.${status.toLowerCase()}` as Parameters<typeof i18n>[0];
  return i18n(key);
}

function formatTime(value?: string | number) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : i18n('task.value.none');
}

function currentTaskRoutePath() {
  return window.location.hash ? window.location.hash.replace(/^#/, '') : window.location.pathname;
}

function pushTaskRoute(path: string) {
  const url = new URL(window.location.href);
  if (url.hash) {
    url.hash = path;
  } else {
    url.pathname = path;
  }
  window.history.pushState({}, '', url.toString());
}

function workspaceTabForRoute(route: TaskWorkspaceRoute, title?: string): TaskWorkspaceTab {
  const fallbackTitle = {
    BOARD: i18n('task.title'),
    ARCHIVE: i18n('task.archive.records'),
    TASK_DETAIL: title || route.entityId || i18n('task.title'),
    TASK_CREATE: i18n('task.create.title'),
    SCHEDULES: title || i18n('task.schedule.title'),
    AGENT_MANAGER: i18n('task.agent.manage'),
    AGENT_EDITOR: title || (route.entityId ? i18n('task.agent.edit') : i18n('task.agent.create')),
  }[route.type];
  return {
    key: taskWorkspaceTabKey(route),
    type: route.type,
    entityId: route.entityId,
    title: fallbackTitle,
    closable: route.type !== 'BOARD',
  };
}

function buildChartDetail(chartJson: Record<string, unknown>): IChartItem {
  const data = Array.isArray(chartJson.data) ? (chartJson.data as Array<Record<string, unknown>>) : [];
  const chartSchema: ChartSchema = {
    chartType: (chartJson.chartType as ChartType) ?? ChartType.Column,
    xField: (chartJson.xField as string) ?? null,
    yField: (chartJson.yField as string) ?? null,
    angleField: (chartJson.angleField as string) ?? null,
    valueField: (chartJson.valueField as string) ?? null,
    colorField: (chartJson.colorField as string) ?? null,
    textField: (chartJson.textField as string) ?? null,
    title: chartJson.title as string,
    summary: chartJson.summary as string,
    themeColorCode: (chartJson.themeColorCode as string) ?? 'v1-baby-blue',
    lineType: LineType.Straight,
    orderByType: OrderByType.DEFAULT,
    orderByRule: OrderByRule.DESC,
    chartOptionCheckbox: ['showLegend', 'showLabel', 'showAxisLine', 'showSplitLine', 'showSymbol'],
    data,
  } as ChartSchema;
  const keys = data.length ? Object.keys(data[0]) : [];
  const sample = data[0] || {};
  return {
    chartSchema,
    metaData: {
      headerList: keys.map((key) => ({
        name: key,
        dataType: typeof sample[key] === 'number' ? TableDataType.NUMERIC : TableDataType.STRING,
      })),
      dataList: data.map((row) => keys.map((key) => row[key])),
      success: true,
      sql: '',
      originalSql: '',
      pageNo: 1,
      pageSize: data.length,
      fuzzyTotal: String(data.length),
      hasNextPage: false,
      refreshTargets: [],
    } as any,
  };
}

function TaskCard({ task, agent, onOpen }: { task: AgentTask; agent?: AgentDefinition; onOpen: () => void }) {
  const { styles } = useStyles();
  return (
    <button type="button" className={styles.taskCard} onClick={onOpen}>
      <div className={styles.taskCardTopline}>
        <span className={styles.taskIdentifier}>TASK-{task.id.slice(0, 6).toUpperCase()}</span>
        <RuntimeBadge agent={agent} compact />
      </div>
      <div className={styles.taskCardTitle}>{task.title}</div>
      {task.description && <div className={styles.taskCardDescription}>{task.description}</div>}
      <div className={styles.taskCardFooter}>
        <div className={styles.taskAgent}>
          <AgentAvatar agent={agent} size={22} />
          <span>{agent?.name || i18n('task.agent.unknown')}</span>
        </div>
        <span className={styles.priorityMark}>
          <Flag size={11} />
          {priorityLabel(task.priority)}
        </span>
      </div>
    </button>
  );
}

interface ArtifactViewProps {
  detail: AgentArtifactDetail;
  publications: AgentTaskDetail['dashboardPublications'];
  dataSources: IConnectionDetails[];
  onPublish: (artifact: AgentArtifactDetail, chartIndex: number) => void;
  hasStructuredChartArtifact: boolean;
}

function ArtifactView({ detail, publications, dataSources, onPublish, hasStructuredChartArtifact }: ArtifactViewProps) {
  const { styles } = useStyles();
  const version = currentArtifactVersion(detail);
  const chartPresentation = extractAgentChartPresentation(artifactMarkdown(detail));
  const structuredCharts = artifactCharts(detail);
  const markdown = chartPresentation.markdown;
  const charts = structuredCharts.length
    ? structuredCharts
    : hasStructuredChartArtifact
      ? []
      : chartPresentation.charts;
  const tables = artifactTables(detail);
  const publicationCount = publications.filter((item) => item.artifactId === detail.artifact.id).length;

  return (
    <section className={styles.artifact}>
      <div className={styles.artifactHeader}>
        <div className={styles.artifactTitle}>
          {detail.artifact.type === 'CHART' ? <FileBarChart size={16} /> : <Rows3 size={16} />}
          <span>{detail.artifact.title}</span>
          <Tag bordered={false}>{i18n('task.artifact.version', version?.version || 1)}</Tag>
        </div>
        {publicationCount > 0 && (
          <Tag bordered={false} color="success">
            {i18n('task.artifact.published', publicationCount)}
          </Tag>
        )}
      </div>

      {markdown && (
        <div className={styles.prose}>
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{markdown}</ReactMarkdown>
        </div>
      )}

      {charts.map((chart, index) => (
        <div key={`${detail.artifact.id}-chart-${index}`} className={styles.chart}>
          <ChartCardBox
            chartDetail={buildChartDetail(chart)}
            isEditPermission={false}
            dropdownProps={{
              menu: {
                items: structuredCharts.length
                  ? [
                      {
                        key: 'publish',
                        label: i18n('task.artifact.publish'),
                        icon: <ExternalLink size={14} />,
                        onClick: () => onPublish(detail, index),
                      },
                    ]
                  : [],
              },
            }}
          />
        </div>
      ))}

      {tables.map((table, index) => (
        <div key={`${detail.artifact.id}-table-${index}`} className={styles.section}>
          {table.title && <h4 className={styles.sectionTitle}>{table.title}</h4>}
          <Table
            size="small"
            rowKey={(_, rowIndex) => String(rowIndex)}
            scroll={{ x: true }}
            pagination={{ pageSize: 20, hideOnSinglePage: true }}
            columns={table.columns.map((column) => ({ title: column, dataIndex: column, key: column }))}
            dataSource={table.rows}
          />
        </div>
      ))}

      {detail.evidence.length > 0 && (
        <details>
          <summary>{i18n('task.evidence.title', detail.evidence.length)}</summary>
          {detail.evidence.map((evidence) => (
            <div key={evidence.id} className={styles.section}>
              <Descriptions
                size="small"
                column={2}
                items={[
                  {
                    key: 'datasource',
                    label: i18n('task.evidence.datasource'),
                    children: dataSourceDisplayName(
                      evidence.dataSourceId,
                      dataSources,
                      i18n('task.scope.datasourceUnavailable', evidence.dataSourceId),
                    ),
                  },
                  {
                    key: 'scope',
                    label: i18n('task.evidence.scope'),
                    children: [evidence.databaseName, evidence.schemaName].filter(Boolean).join(' / ') || '-',
                  },
                  { key: 'time', label: i18n('task.evidence.executedAt'), children: formatTime(evidence.executedAt) },
                ]}
              />
              <pre className={styles.sql}>{evidence.sqlSnapshot}</pre>
            </div>
          ))}
        </details>
      )}
    </section>
  );
}

function PendingApproval({
  approval,
  detail,
  onDecision,
}: {
  approval: AgentApproval;
  detail: AgentTaskDetail;
  onDecision: (approval: AgentApproval, decision: 'APPROVE' | 'REJECT') => void;
}) {
  const { styles } = useStyles();
  const proposal = detail.sqlProposals.find((item) => item.id === approval.proposalId);
  return (
    <div className={styles.approval}>
      <Space>
        <ShieldCheck size={17} />
        <strong>{i18n('task.approval.required')}</strong>
        {proposal && <Tag color={proposal.riskLevel === 'LOW' ? 'blue' : 'warning'}>{proposal.riskLevel}</Tag>}
      </Space>
      {proposal && <pre className={styles.sql}>{proposal.sqlSnapshot}</pre>}
      <div className={styles.approvalActions}>
        <Button onClick={() => onDecision(approval, 'REJECT')}>{i18n('task.approval.reject')}</Button>
        <Button type="primary" onClick={() => onDecision(approval, 'APPROVE')}>
          {i18n('task.approval.approve')}
        </Button>
      </div>
    </div>
  );
}

export default function Tasks() {
  const { styles } = useStyles();
  const { modal } = App.useApp();
  const tasksPageActive = useGlobalStore((state) =>
    state.mainPageActiveTab === 'tasks' && state.settingPageActiveTab === false,
  );
  const initialWorkspaceRoute = useRef(parseTaskWorkspaceRoute(currentTaskRoutePath())).current;
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [archivedTasks, setArchivedTasks] = useState<AgentTask[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [dataSources, setDataSources] = useState<IConnectionDetails[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>('board');
  const [taskTitleFilter, setTaskTitleFilter] = useState('');
  const [taskAgentFilter, setTaskAgentFilter] = useState<string[]>([]);
  const [taskStatusFilter, setTaskStatusFilter] = useState<TaskBoardColumnKey[]>([]);
  const [archiveView, setArchiveView] = useState(initialWorkspaceRoute.type === 'ARCHIVE');
  const [selectedTaskId, setSelectedTaskId] = useState<string | undefined>(() =>
    initialWorkspaceRoute.type === 'TASK_DETAIL' ? initialWorkspaceRoute.entityId : undefined,
  );
  const [detail, setDetail] = useState<AgentTaskDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const detailRequestTaskId = useRef<string>();
  const [scheduleRoute, setScheduleRoute] = useState(() => parseTaskScheduleRoute(currentTaskRoutePath()));
  const [publishTarget, setPublishTarget] = useState<{ artifact: AgentArtifactDetail; chartIndex: number }>();
  const [dashboards, setDashboards] = useState<IDashboardItem[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [createForm] = Form.useForm();
  const [publishForm] = Form.useForm();
  const [contextForm] = Form.useForm();
  const [messageDraft, setMessageDraft] = useState('');
  const [mentionedAgentId, setMentionedAgentId] = useState<string>();
  const [expandedActivities, setExpandedActivities] = useState<Set<string>>(new Set());
  const [executionOpen, setExecutionOpen] = useState(true);
  const [pastRunsOpen, setPastRunsOpen] = useState(false);
  const [workspaceTabs, setWorkspaceTabs] = useState<TaskWorkspaceTab[]>(() =>
    upsertTaskWorkspaceTab(
      [workspaceTabForRoute({ type: 'BOARD' })],
      workspaceTabForRoute(initialWorkspaceRoute),
    ),
  );
  const [activeWorkspaceTabKey, setActiveWorkspaceTabKey] = useState(() => taskWorkspaceTabKey(initialWorkspaceRoute));
  const activeWorkspaceTab = workspaceTabs.find((tab) => tab.key === activeWorkspaceTabKey) || workspaceTabs[0];

  const agentById = useMemo(() => new Map(agents.map((agent) => [agent.id, agent])), [agents]);
  const filteredTasks = useMemo(
    () => filterTasks(tasks, {
      title: taskTitleFilter,
      agentIds: taskAgentFilter,
      boardColumns: taskStatusFilter,
    }),
    [taskAgentFilter, taskStatusFilter, taskTitleFilter, tasks],
  );
  const groupedTasks = useMemo(() => groupTasks(filteredTasks), [filteredTasks]);
  const hasTaskFilters = Boolean(taskTitleFilter.trim() || taskAgentFilter.length || taskStatusFilter.length);
  const clearTaskFilters = () => {
    setTaskTitleFilter('');
    setTaskAgentFilter([]);
    setTaskStatusFilter([]);
  };

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const [taskItems, archivedItems, agentItems] = await Promise.all([
        agentService.listTasks(undefined as void),
        agentService.listArchivedTasks(undefined as void),
        agentService.listAgents(undefined as void),
        listAIModelConfigs(),
      ]);
      setTasks(taskItems || []);
      setArchivedTasks(archivedItems || []);
      setAgents((agentItems || []).filter((agent) => agent.status === 'ACTIVE'));
    } catch {
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDetail = useCallback(async (taskId: string, silent = false) => {
    detailRequestTaskId.current = taskId;
    if (!silent) setDetailLoading(true);
    try {
      const result = await agentService.getTask({ taskId });
      if (detailRequestTaskId.current !== taskId) return;
      setDetail(result);
      cacheAgentTaskDetail(result);
      setTasks((current) => upsertTask(current, result.task));
    } catch {
      if (!silent) feedback.error(i18n('task.detail.loadFailed'));
    } finally {
      if (detailRequestTaskId.current === taskId) {
        detailRequestTaskId.current = undefined;
        if (!silent) setDetailLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void connectionService.getList({ pageNo: 1, pageSize: 500 })
      .then((result) => setDataSources(result.data || []))
      .catch(() => setDataSources([]));
  }, []);

  useEffect(() => {
    const handleTaskCreated = (event: Event) => {
      const created = (event as CustomEvent<AgentTaskDetail>).detail;
      if (!created?.task) return;
      cacheAgentTaskDetail(created);
      setTasks((current) => upsertTask(current, created.task));
    };
    window.addEventListener(AGENT_TASK_CREATED_EVENT, handleTaskCreated);
    return () => window.removeEventListener(AGENT_TASK_CREATED_EVENT, handleTaskCreated);
  }, []);

  useEffect(() => {
    const routePath = currentTaskRoutePath();
    const path = routePath.split('/');
    const workspaceRoute = parseTaskWorkspaceRoute(routePath);
    const routedTitle = workspaceRoute.type === 'TASK_DETAIL'
      ? tasks.find((task) => task.id === workspaceRoute.entityId)?.title
      : workspaceRoute.type === 'AGENT_EDITOR'
        ? agents.find((agent) => agent.id === workspaceRoute.entityId)?.name
        : undefined;
    const routedTab = workspaceTabForRoute(workspaceRoute, routedTitle);
    if (activeWorkspaceTabKey !== routedTab.key) setActiveWorkspaceTabKey(routedTab.key);
    setWorkspaceTabs((current) => upsertTaskWorkspaceTab(current, routedTab));
    const nextScheduleRoute = parseTaskScheduleRoute(routePath);
    if (nextScheduleRoute.open) {
      if (
        !scheduleRoute.open
        || scheduleRoute.createMode !== nextScheduleRoute.createMode
        || scheduleRoute.scheduleId !== nextScheduleRoute.scheduleId
      ) {
        setScheduleRoute(nextScheduleRoute);
      }
      if (archiveView) setArchiveView(false);
      if (selectedTaskId) {
        setSelectedTaskId(undefined);
        setDetail(undefined);
      }
      return;
    }
    if (workspaceRoute.type === 'TASK_CREATE' || workspaceRoute.type === 'AGENT_MANAGER' || workspaceRoute.type === 'AGENT_EDITOR') {
      if (scheduleRoute.open) setScheduleRoute(nextScheduleRoute);
      if (archiveView) setArchiveView(false);
      if (selectedTaskId) {
        setSelectedTaskId(undefined);
        setDetail(undefined);
      }
      return;
    }
    if (scheduleRoute.open) setScheduleRoute(nextScheduleRoute);
    if (path[1] === 'tasks' && path[2] === 'archive') {
      if (!archiveView) setArchiveView(true);
      if (selectedTaskId) {
        setSelectedTaskId(undefined);
        setDetail(undefined);
      }
      return;
    }
    if (
      path[1] === 'tasks' &&
      path[2] &&
      (path[2] !== selectedTaskId || (!detail && detailRequestTaskId.current !== path[2]))
    ) {
      if (archiveView) setArchiveView(false);
      setSelectedTaskId(path[2]);
      const cached = getCachedAgentTaskDetail(path[2]);
      if (cached) {
        setDetail(cached);
        setTasks((current) => upsertTask(current, cached.task));
        void loadDetail(path[2], true);
      } else {
        void loadDetail(path[2]);
      }
      return;
    }
    if (path[1] === 'tasks' && !path[2] && archiveView) setArchiveView(false);
  });

  const openWorkspaceTab = useCallback((route: TaskWorkspaceRoute, title?: string) => {
    const tab = workspaceTabForRoute(route, title);
    setWorkspaceTabs((current) => upsertTaskWorkspaceTab(current, tab));
    setActiveWorkspaceTabKey(tab.key);
    pushTaskRoute(taskWorkspaceRoutePath(tab));
    return tab;
  }, []);

  const setWorkspaceTabDirty = useCallback((key: string, dirty: boolean) => {
    setWorkspaceTabs((current) => current.map((tab) => tab.key === key ? { ...tab, dirty } : tab));
  }, []);

  const openSchedules = useCallback((scheduleId?: string) => {
    setScheduleRoute(parseTaskScheduleRoute(taskScheduleRoutePath(scheduleId)));
    setSelectedTaskId(undefined);
    setDetail(undefined);
    setArchiveView(false);
    openWorkspaceTab({ type: 'SCHEDULES', entityId: scheduleId });
  }, [openWorkspaceTab]);

  const closeSchedules = useCallback(() => {
    setScheduleRoute({ open: false, createMode: false });
    openWorkspaceTab({ type: 'BOARD' });
  }, [openWorkspaceTab]);

  const openTask = useCallback(
    (taskId: string, initialDetail?: AgentTaskDetail) => {
      setScheduleRoute({ open: false, createMode: false });
      setSelectedTaskId(taskId);
      if (initialDetail) {
        setDetail(initialDetail);
        setDetailLoading(false);
      }
      openWorkspaceTab({ type: 'TASK_DETAIL', entityId: taskId }, initialDetail?.task.title || tasks.find((task) => task.id === taskId)?.title);
      if (!initialDetail) {
        void loadDetail(taskId);
      }
    },
    [loadDetail, openWorkspaceTab, tasks],
  );

  const closeTask = useCallback(() => {
    setSelectedTaskId(undefined);
    setDetail(undefined);
    openWorkspaceTab({ type: 'BOARD' });
  }, [openWorkspaceTab]);

  const openArchive = useCallback((open: boolean) => {
    setArchiveView(open);
    setScheduleRoute({ open: false, createMode: false });
    setSelectedTaskId(undefined);
    setDetail(undefined);
    openWorkspaceTab({ type: open ? 'ARCHIVE' : 'BOARD' });
  }, [openWorkspaceTab]);

  const openTaskCreate = useCallback(() => {
    setScheduleRoute({ open: false, createMode: false });
    setSelectedTaskId(undefined);
    setDetail(undefined);
    openWorkspaceTab({ type: 'TASK_CREATE' });
  }, [openWorkspaceTab]);

  const openAgentManager = useCallback(() => {
    setScheduleRoute({ open: false, createMode: false });
    setSelectedTaskId(undefined);
    setDetail(undefined);
    openWorkspaceTab({ type: 'AGENT_MANAGER' });
  }, [openWorkspaceTab]);

  const openAgentEditor = useCallback((agent?: AgentDefinition) => {
    setScheduleRoute({ open: false, createMode: false });
    setSelectedTaskId(undefined);
    setDetail(undefined);
    openWorkspaceTab({ type: 'AGENT_EDITOR', entityId: agent?.id }, agent?.name);
  }, [openWorkspaceTab]);

  const activateWorkspaceTab = useCallback((tab: TaskWorkspaceTab) => {
    if (tab.type === 'TASK_DETAIL' && tab.entityId) {
      openTask(tab.entityId);
      return;
    }
    if (tab.type === 'SCHEDULES') {
      openSchedules(tab.entityId);
      return;
    }
    if (tab.type === 'ARCHIVE') {
      openArchive(true);
      return;
    }
    if (tab.type === 'TASK_CREATE') {
      openTaskCreate();
      return;
    }
    if (tab.type === 'AGENT_MANAGER') {
      openAgentManager();
      return;
    }
    if (tab.type === 'AGENT_EDITOR') {
      openAgentEditor(agents.find((agent) => agent.id === tab.entityId));
      return;
    }
    openArchive(false);
  }, [agents, openAgentEditor, openAgentManager, openArchive, openSchedules, openTask, openTaskCreate]);

  const confirmCloseWorkspaceTabs = useCallback((tabsToClose: Array<{ key: string }>) => {
    if (!tabsToClose.some((item) => workspaceTabs.find((tab) => tab.key === item.key)?.dirty)) {
      return true;
    }
    return new Promise<boolean>((resolve) => {
      modal.confirm({
        title: i18n('task.workspace.unsavedTitle'),
        content: i18n('task.workspace.unsavedContent'),
        okText: i18n('task.workspace.discard'),
        okButtonProps: { danger: true },
        cancelText: i18n('task.action.cancel'),
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
  }, [modal, workspaceTabs]);

  const handleWorkspaceTabsEdit = useCallback((
    action: 'add' | 'remove',
    removed?: Array<{ key: string | number }>,
    nextItems?: Array<{ key: string | number }>,
  ) => {
    if (action !== 'remove' || !removed?.length) return;
    const removedKeys = removed.map((item) => String(item.key));
    const nextTabs = workspaceTabs.filter((tab) => !removedKeys.includes(tab.key));
    setWorkspaceTabs(nextTabs);
    const nextKey = nextTaskWorkspaceTabKey(workspaceTabs, removedKeys[0], activeWorkspaceTabKey);
    const nextTab = nextTabs.find((tab) => tab.key === nextKey) || nextTabs[0];
    if (nextTab) activateWorkspaceTab(nextTab);
    void nextItems;
  }, [activateWorkspaceTab, activeWorkspaceTabKey, workspaceTabs]);

  useEffect(() => {
    const handleHistoryNavigation = () => {
      const route = parseTaskWorkspaceRoute(currentTaskRoutePath());
      const tab = workspaceTabForRoute(route);
      setWorkspaceTabs((current) => upsertTaskWorkspaceTab(current, tab));
      setActiveWorkspaceTabKey(tab.key);
      if (route.type === 'TASK_DETAIL' && route.entityId) {
        setSelectedTaskId(route.entityId);
        void loadDetail(route.entityId);
      } else {
        setSelectedTaskId(undefined);
        setDetail(undefined);
      }
      setScheduleRoute(route.type === 'SCHEDULES'
        ? parseTaskScheduleRoute(taskWorkspaceRoutePath(tab))
        : { open: false, createMode: false });
      setArchiveView(route.type === 'ARCHIVE');
    };
    window.addEventListener('popstate', handleHistoryNavigation);
    window.addEventListener('hashchange', handleHistoryNavigation);
    return () => {
      window.removeEventListener('popstate', handleHistoryNavigation);
      window.removeEventListener('hashchange', handleHistoryNavigation);
    };
  }, [loadDetail]);

  useEffect(() => {
    if (!workspaceTabs.some((tab) => tab.dirty)) return;
    const confirmUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', confirmUnload);
    return () => window.removeEventListener('beforeunload', confirmUnload);
  }, [workspaceTabs]);

  const openOriginConversation = useCallback(async () => {
    const sessionId = detail?.task.originSessionId;
    if (!sessionId) return;
    try {
      const sessions = (await aiStreamService.getChatSessions(undefined as void)) || [];
      const session = sessions.find((item) => item.id === sessionId);
      if (!session) {
        feedback.warning(i18n('task.origin.conversationUnavailable'));
        return;
      }
      setPendingConversationTarget({
        sessionId: session.id,
        messageId: detail?.task.originMessageId,
      });
      window.dispatchEvent(new CustomEvent('app:navigateTo', {
        detail: { page: 'stream', pathName: `/stream/${session.id}` },
      }));
      window.dispatchEvent(new CustomEvent('stream:loadSession', {
        detail: {
          sessionId: session.id,
          title: session.title,
          messageId: detail?.task.originMessageId,
        },
      }));
    } catch {
      feedback.warning(i18n('task.origin.conversationUnavailable'));
    }
  }, [detail?.task.originMessageId, detail?.task.originSessionId]);

  const createTask = async () => {
    const values = await createForm.validateFields();
    const agent = agentById.get(values.assigneeAgentId);
    const scopeIndexes: number[] = values.scopeIndexes || [];
    setSubmitting(true);
    try {
      const created = await agentService.createTask({
        title: values.title,
        description: values.description,
        acceptanceCriteria: values.acceptanceCriteria,
        priority: values.priority || 0,
        assigneeAgentId: values.assigneeAgentId,
        originType: 'BOARD',
        dataScopeSnapshot: scopeIndexes.map((index) => agent!.dataScopes[index]),
      });
      setTasks((current) => [created.task, ...current]);
      setWorkspaceTabDirty('task:new', false);
      createForm.resetFields();
      feedback.success(i18n('task.create.success'));
      openTask(created.task.id, created);
    } finally {
      setSubmitting(false);
    }
  };

  const transition = async (targetStatus: AgentTaskStatus) => {
    if (!detail) return;
    setSubmitting(true);
    try {
      const task = await agentService.transitionTask({
        taskId: detail.task.id,
        expectedRevision: detail.task.revision,
        targetStatus,
      });
      setDetail({ ...detail, task });
      setTasks((current) => current.map((item) => (item.id === task.id ? task : item)));
      feedback.success(i18n('task.transition.success'));
    } finally {
      setSubmitting(false);
    }
  };

  const archiveTask = async () => {
    if (!detail) return;
    modal.confirm({
      title: i18n('task.archive.confirmTitle'),
      content: i18n('task.archive.confirmContent'),
      okText: i18n('task.archive.action'),
      cancelText: i18n('task.action.cancel'),
      onOk: async () => {
        const archived = await agentService.archiveTask({
          taskId: detail.task.id,
          expectedRevision: detail.task.revision,
        });
        setTasks((current) => current.filter((item) => item.id !== archived.id));
        setArchivedTasks((current) => [archived, ...current.filter((item) => item.id !== archived.id)]);
        feedback.success(i18n('task.archive.success'));
        openArchive(true);
      },
    });
  };

  const deleteArchivedTask = (task: AgentTask) => {
    modal.confirm({
      title: i18n('task.archive.deleteConfirmTitle'),
      content: i18n('task.archive.deleteConfirmContent', task.title),
      okText: i18n('task.archive.delete'),
      okButtonProps: { danger: true },
      cancelText: i18n('task.action.cancel'),
      onOk: async () => {
        await agentService.deleteArchivedTask({ taskId: task.id, expectedRevision: task.revision });
        setArchivedTasks((current) => current.filter((item) => item.id !== task.id));
        feedback.success(i18n('task.archive.deleteSuccess'));
      },
    });
  };

  const decide = async (approval: AgentApproval, decision: 'APPROVE' | 'REJECT') => {
    setSubmitting(true);
    try {
      await agentService.decideApproval({
        approvalId: approval.id,
        expectedRevision: approval.revision,
        decision,
      });
      if (selectedTaskId) await loadDetail(selectedTaskId);
      feedback.success(i18n('task.approval.success'));
    } finally {
      setSubmitting(false);
    }
  };

  const cancelRun = async (run: AgentRun) => {
    setSubmitting(true);
    try {
      await agentService.cancelRun({ runId: run.id });
      if (selectedTaskId) await loadDetail(selectedTaskId);
    } finally {
      setSubmitting(false);
    }
  };

  const continueConversation = async () => {
    if (!detail) return;
    const content = messageDraft.trim();
    if (!content) return;
    setSubmitting(true);
    try {
      const nextDetail = await agentService.continueTask({
        taskId: detail.task.id,
        content,
        agentId: mentionedAgentId,
      });
      setDetail(nextDetail);
      setTasks((current) =>
        current.map((task) => (task.id === nextDetail.task.id ? nextDetail.task : task)),
      );
      setMessageDraft('');
      setMentionedAgentId(undefined);
      feedback.success(i18n('task.conversation.success'));
    } finally {
      setSubmitting(false);
    }
  };

  const appendContext = async () => {
    if (!detail) return;
    const values = await contextForm.validateFields();
    setSubmitting(true);
    try {
      await agentService.appendTaskContext({
        taskId: detail.task.id,
        type: values.type as AgentTaskContextType,
        title: values.title,
        content: values.content,
        attachmentName: values.attachmentName,
        attachmentMimeType: values.attachmentMimeType,
      });
      contextForm.resetFields();
      await loadDetail(detail.task.id);
      feedback.success(i18n('task.context.addSuccess'));
    } finally {
      setSubmitting(false);
    }
  };

  const syncTaskScopes = async () => {
    if (!detail) return;
    setSubmitting(true);
    try {
      const task = await agentService.syncTaskScopes({
        taskId: detail.task.id,
        expectedRevision: detail.task.revision,
      });
      setDetail({ ...detail, task });
      setTasks((current) => current.map((item) => (item.id === task.id ? task : item)));
      feedback.success(i18n('task.scope.syncSuccess'));
    } finally {
      setSubmitting(false);
    }
  };

  const openPublish = async (artifact: AgentArtifactDetail, chartIndex: number) => {
    setPublishTarget({ artifact, chartIndex });
    publishForm.resetFields();
    if (!dashboards.length) {
      const result = await getDashboardList({ pageNo: 1, pageSize: 100 });
      setDashboards(result.data || []);
    }
  };

  const publish = async () => {
    if (!publishTarget || !detail) return;
    const values = await publishForm.validateFields();
    setSubmitting(true);
    try {
      await agentService.publishArtifact({
        artifactId: publishTarget.artifact.artifact.id,
        artifactVersion: publishTarget.artifact.artifact.currentVersion,
        chartIndex: publishTarget.chartIndex,
        dashboardId: values.dashboardId,
        contentMode: values.contentMode as AgentArtifactContentMode,
      });
      setPublishTarget(undefined);
      await loadDetail(detail.task.id);
      feedback.success(i18n('task.artifact.publishSuccess'));
    } finally {
      setSubmitting(false);
    }
  };

  const pendingApprovals = detail?.approvals.filter((approval) => approval.status === 'PENDING') || [];
  const events = detail
    ? Object.values(detail.eventsByRunId)
        .flat()
        .sort((left, right) => left.sequence - right.sequence)
    : [];
  const currentRun = detail?.runs.find((run) => run.id === detail.task.currentRunId) || detail?.runs[0];
  const selectedAgent = detail ? agentById.get(detail.task.assigneeAgentId) : undefined;
  const activeRun = Boolean(
    currentRun && ['QUEUED', 'DISPATCHED', 'RUNNING', 'WAITING_APPROVAL'].includes(currentRun.status),
  );
  const conversationItems = useMemo(() => {
    if (!detail) return [];
    const items: Array<{
      id: string;
      kind: 'created' | 'user' | 'agent' | 'error';
      content: string;
      occurredAt: string | number;
      run?: AgentRun;
      contextType?: AgentTaskContextType;
    }> = [
      {
        id: `created-${detail.task.id}`,
        kind: 'created',
        content: i18n('task.conversation.created'),
        occurredAt: detail.task.gmtCreate,
      },
    ];
    detail.contexts.forEach((context) => {
      items.push({
        id: `context-${context.id}`,
        kind: 'user',
        content: context.content,
        occurredAt: context.createdAt,
        contextType: context.type,
      });
    });
    detail.runs.forEach((run) => {
      const runEvents = detail.eventsByRunId[run.id] || [];
      const answer = cleanAgentMarkdown(runEvents
        .filter((event) => event.type === 'MESSAGE_DELTA')
        .map((event) => event.content || '')
        .join(''));
      const lastEventAt = runEvents[runEvents.length - 1]?.occurredAt;
      if (answer) {
        items.push({
          id: `answer-${run.id}`,
          kind: 'agent',
          content: answer,
          occurredAt: run.completedAt || lastEventAt || run.startedAt || detail.task.gmtCreate,
          run,
        });
      } else if (run.failureReason) {
        items.push({
          id: `error-${run.id}`,
          kind: 'error',
          content: run.failureReason,
          occurredAt: run.completedAt || lastEventAt || run.startedAt || detail.task.gmtCreate,
          run,
        });
      }
    });
    return items.sort((left, right) => dayjs(left.occurredAt).valueOf() - dayjs(right.occurredAt).valueOf());
  }, [detail]);

  const activeRuns = useMemo(
    () => detail?.runs.filter((run) => ['QUEUED', 'DISPATCHED', 'RUNNING', 'WAITING_APPROVAL'].includes(run.status)) || [],
    [detail],
  );
  const pastRuns = useMemo(
    () => detail?.runs.filter((run) => !['QUEUED', 'DISPATCHED', 'RUNNING', 'WAITING_APPROVAL'].includes(run.status)) || [],
    [detail],
  );

  useEffect(() => {
    if (!detail) return;
    const agentItems = conversationItems.filter((item) => item.kind === 'agent' || item.kind === 'error');
    setExpandedActivities(new Set(agentItems.slice(-1).map((item) => item.id)));
    setPastRunsOpen(false);
  }, [detail?.task.id]);

  useEffect(() => {
    if (
      !tasksPageActive
      || !selectedTaskId
      || !shouldRefreshTaskDetail(activeWorkspaceTab, selectedTaskId, activeRun)
    ) return undefined;
    const timer = window.setInterval(() => void loadDetail(selectedTaskId, true), 2000);
    return () => window.clearInterval(timer);
  }, [activeRun, activeWorkspaceTab, loadDetail, selectedTaskId, tasksPageActive]);

  const _detailTabs = detail
    ? [
        {
          key: 'overview',
          label: i18n('task.detail.overview'),
          children: (
            <div className={styles.detailBody}>
              <div className={styles.detailShell}>
                <div className={styles.detailMain}>
                  <section className={styles.detailDescription}>
                    <h3>{i18n('task.field.description')}</h3>
                    <p>{detail.task.description || i18n('task.detail.noDescription')}</p>
                  </section>
                  <section className={styles.detailDescription}>
                    <h3>{i18n('task.field.acceptanceCriteria')}</h3>
                    <p>{detail.task.acceptanceCriteria || i18n('task.detail.noCriteria')}</p>
                  </section>
                  <h3 className={styles.sectionTitle}>{i18n('task.scope.title')}</h3>
                  {detail.task.dataScopeSnapshot.length ? (
                    <div className={styles.scopeList}>
                      {detail.task.dataScopeSnapshot.map((scope, index) => (
                        <div className={styles.scope} key={`${scope.dataSourceId}-${index}`}>
                          <strong>
                            {dataSourceDisplayName(
                              scope.dataSourceId,
                              dataSources,
                              i18n('task.scope.datasourceUnavailable', scope.dataSourceId),
                            )}
                          </strong>
                          <div>
                            {[scope.databaseName, scope.schemaName].filter(Boolean).join(' / ') ||
                              i18n('task.scope.all')}
                          </div>
                          <div>
                            {scope.tableNames.length
                              ? i18n('task.scope.tableCount', scope.tableNames.length)
                              : i18n('task.scope.namespaceWide')}
                          </div>
                          <ApprovalModeTag mode={scope.approvalMode} />
                        </div>
                      ))}
                    </div>
                  ) : (
                    <Alert type="info" showIcon message={i18n('task.scope.empty')} />
                  )}
                </div>
                <aside className={styles.detailAside}>
                  <div className={styles.propertyBlock}>
                    <h4>{i18n('task.detail.properties')}</h4>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.status')}</span>
                      <Tag bordered={false} color={statusColor[detail.task.status]}>
                        {statusLabel(detail.task.status)}
                      </Tag>
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.agent')}</span>
                      <span className={styles.propertyAgent}>
                        <AgentAvatar agent={selectedAgent} size={22} />
                        {selectedAgent?.name || i18n('task.agent.unknown')}
                      </span>
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.agent.runtime')}</span>
                      <RuntimeBadge agent={selectedAgent} run={currentRun} />
                    </div>
                    {currentRun && (
                      <div className={styles.propertyRow}>
                        <span>{i18n('task.run.current')}</span>
                        <RunStatusMark status={currentRun.status} />
                      </div>
                    )}
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.priority')}</span>
                      <span>{priorityLabel(detail.task.priority)}</span>
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.origin')}</span>
                      {detail.task.originType === 'CHAT' && detail.task.originSessionId ? (
                        <Button type="link" size="small" onClick={() => void openOriginConversation()}>
                          {i18n('task.origin.openConversation')}
                        </Button>
                      ) : <span>{detail.task.originType}</span>}
                    </div>
                  </div>
                  <div className={styles.propertyBlock}>
                    <h4>{i18n('task.detail.timestamps')}</h4>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.updatedAt')}</span>
                      <span>{formatTime(detail.task.gmtModified)}</span>
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.run.startedAt')}</span>
                      <span>{formatTime(currentRun?.startedAt)}</span>
                    </div>
                  </div>
                </aside>
              </div>
            </div>
          ),
        },
        {
          key: 'results',
          label: `${i18n('task.detail.results')} (${detail.artifacts.length})`,
          children: (
            <div className={styles.detailBody}>
              {pendingApprovals.map((approval) => (
                <PendingApproval key={approval.id} approval={approval} detail={detail} onDecision={decide} />
              ))}
              {detail.artifacts.length ? (
                detail.artifacts.map((artifact) => (
                  <ArtifactView
                    key={artifact.artifact.id}
                    detail={artifact}
                    publications={detail.dashboardPublications}
                    dataSources={dataSources}
                    onPublish={openPublish}
                    hasStructuredChartArtifact={detail.artifacts.some(
                      (item) => item.artifact.createdByRunId === artifact.artifact.createdByRunId
                        && artifactCharts(item).length > 0,
                    )}
                  />
                ))
              ) : (
                <Empty description={i18n('task.artifact.empty')} />
              )}
            </div>
          ),
        },
        {
          key: 'context',
          label: `${i18n('task.detail.context')} (${detail.contexts.length})`,
          children: (
            <div className={styles.detailBody}>
              <Form form={contextForm} layout="vertical" initialValues={{ type: 'COMMENT' }}>
                <div className={styles.contextComposer}>
                  <Form.Item name="type" label={i18n('task.context.type')} rules={[{ required: true }]}>
                    <Select
                      options={(['PINNED', 'COMMENT', 'ATTACHMENT'] as AgentTaskContextType[]).map((type) => ({
                        value: type,
                        label: i18n(`task.context.${type.toLowerCase()}` as Parameters<typeof i18n>[0]),
                      }))}
                    />
                  </Form.Item>
                  <Form.Item name="title" label={i18n('task.context.title')}>
                    <Input maxLength={255} />
                  </Form.Item>
                  <Form.Item noStyle shouldUpdate={(before, after) => before.type !== after.type}>
                    {({ getFieldValue }) =>
                      getFieldValue('type') === 'ATTACHMENT' ? (
                        <div className={styles.contextAttachmentFields}>
                          <Form.Item
                            name="attachmentName"
                            label={i18n('task.context.attachmentName')}
                            rules={[{ required: true }]}
                          >
                            <Input maxLength={512} />
                          </Form.Item>
                          <Form.Item name="attachmentMimeType" label={i18n('task.context.attachmentType')}>
                            <Input maxLength={255} placeholder="text/csv" />
                          </Form.Item>
                        </div>
                      ) : null
                    }
                  </Form.Item>
                  <Form.Item name="content" label={i18n('task.context.content')} rules={[{ required: true }]}>
                    <Input.TextArea rows={4} maxLength={200000} showCount />
                  </Form.Item>
                  <div className={styles.contextActions}>
                    <Button type="primary" loading={submitting} onClick={() => void appendContext()}>
                      {i18n('task.context.add')}
                    </Button>
                  </div>
                </div>
              </Form>
              {detail.contexts.length ? (
                <div className={styles.contextList}>
                  {[...detail.contexts].reverse().map((context) => (
                    <article className={styles.contextItem} key={context.id}>
                      <div className={styles.contextHeader}>
                        <Space>
                          {context.type === 'PINNED' ? <Pin size={14} /> : <MessageSquareText size={14} />}
                          <Tag bordered={false}>
                            {i18n(`task.context.${context.type.toLowerCase()}` as Parameters<typeof i18n>[0])}
                          </Tag>
                          <strong>{context.title || context.attachmentName || i18n('task.context.untitled')}</strong>
                        </Space>
                        <span>{formatTime(context.createdAt)}</span>
                      </div>
                      <div className={styles.contextContent}>{context.content}</div>
                    </article>
                  ))}
                </div>
              ) : (
                <Empty description={i18n('task.context.empty')} />
              )}
            </div>
          ),
        },
        {
          key: 'activity',
          label: i18n('task.detail.activity'),
          children: events.length ? (
            <Timeline
              items={events.map((event) => ({
                color: event.type === 'ERROR' ? 'red' : event.type === 'STATUS' ? 'blue' : 'gray',
                children: (
                  <div className={styles.activityContent}>
                    <Space>
                      <Tag bordered={false}>{event.type}</Tag>
                      <span>{formatTime(event.occurredAt)}</span>
                    </Space>
                    {event.content && <div>{event.content}</div>}
                  </div>
                ),
              }))}
            />
          ) : (
            <Empty description={i18n('task.activity.empty')} />
          ),
        },
        {
          key: 'runs',
          label: `${i18n('task.detail.runs')} (${detail.runs.length})`,
          children: (
            <Table
              size="small"
              rowKey="id"
              pagination={false}
              dataSource={detail.runs}
              columns={[
                { title: i18n('task.run.attempt'), dataIndex: 'attempt', width: 70 },
                {
                  title: i18n('task.field.status'),
                  dataIndex: 'status',
                  render: (value) => <RunStatusMark status={value} />,
                },
                { title: i18n('task.run.startedAt'), dataIndex: 'startedAt', render: formatTime },
                { title: i18n('task.run.completedAt'), dataIndex: 'completedAt', render: formatTime },
                {
                  title: i18n('task.field.action'),
                  width: 90,
                  render: (_, run: AgentRun) =>
                    ['QUEUED', 'DISPATCHED', 'RUNNING', 'WAITING_APPROVAL'].includes(run.status) ? (
                      <Button size="small" danger loading={submitting} onClick={() => cancelRun(run)}>
                        {i18n('task.run.cancel')}
                      </Button>
                    ) : null,
                },
              ]}
            />
          ),
        },
      ]
    : [];

  if (loading) {
    return (
      <div className={styles.container}>
        <div className={styles.content}>
          <Skeleton active paragraph={{ rows: 10 }} />
        </div>
      </div>
    );
  }

  if (loadError) {
    return (
      <div className={styles.error}>
        <Alert
          type="error"
          showIcon
          message={i18n('task.load.failed')}
          action={<Button onClick={() => void load()}>{i18n('task.action.retry')}</Button>}
        />
      </div>
    );
  }

  return (
    <div className={styles.container}>
      <CustomTabs
        className={styles.taskWorkspaceTabs}
        items={workspaceTabs.map((tab) => ({
          key: tab.key,
          label: tab.dirty ? `${tab.title} •` : tab.title,
          canClosed: tab.closable,
          prefixIcon: tab.type === 'TASK_DETAIL'
            ? <span className={styles.taskWorkspaceTabIcon}><ListChecks size={14} /></span>
            : tab.type === 'SCHEDULES'
              ? <span className={styles.taskWorkspaceTabIcon}><CalendarClock size={14} /></span>
              : tab.type === 'AGENT_MANAGER' || tab.type === 'AGENT_EDITOR'
                ? <span className={styles.taskWorkspaceTabIcon}><Bot size={14} /></span>
                : <span className={styles.taskWorkspaceTabIcon}><FolderKanban size={14} /></span>,
        }))}
        activeKey={activeWorkspaceTabKey}
        hideAdd
        height={40}
        tabMaxWidth="220px"
        onChange={(key) => {
          const tab = workspaceTabs.find((item) => item.key === String(key));
          if (tab) activateWorkspaceTab(tab);
        }}
        beforeRemove={confirmCloseWorkspaceTabs}
        onEdit={handleWorkspaceTabsEdit}
      />
      <div className={styles.taskWorkspaceContent}>
      {activeWorkspaceTab?.type === 'TASK_CREATE'
        || activeWorkspaceTab?.type === 'AGENT_MANAGER'
        || activeWorkspaceTab?.type === 'AGENT_EDITOR'
        || activeWorkspaceTab?.type === 'SCHEDULES' ? null : selectedTaskId ? (
        <>
          <header className={styles.detailPageHeader}>
            <Button type="text" icon={<ArrowLeft size={16} />} onClick={closeTask}>
              {i18n('task.detail.back')}
            </Button>
            {detail && (
              <div className={styles.detailPageActions}>
                <Tag color={statusColor[detail.task.status]}>{statusLabel(detail.task.status)}</Tag>
                <RuntimeBadge agent={selectedAgent} run={currentRun} />
                {TASK_TRANSITIONS[detail.task.status].length > 0 && (
                  <Select
                    size="small"
                    placeholder={i18n('task.transition.action')}
                    loading={submitting}
                    value={undefined}
                    style={{ width: 150 }}
                    onChange={transition}
                    options={TASK_TRANSITIONS[detail.task.status].map((status) => ({
                      value: status,
                      label: statusLabel(status),
                    }))}
                  />
                )}
                {!detail.task.archivedAt && !activeRun && (
                  <Button size="small" icon={<Archive size={14} />} onClick={() => void archiveTask()}>
                    {i18n('task.archive.action')}
                  </Button>
                )}
              </div>
            )}
          </header>
          <main className={styles.detailPageContent}>
            {detailLoading || !detail ? (
              <Skeleton active paragraph={{ rows: 12 }} />
            ) : (
              <div className={styles.detailPageWorkspace}>
                <div className={styles.taskWorkbenchMain}>
                  <div className={styles.taskDocument}>
                    <div className={styles.detailBreadcrumb}>TASK-{detail.task.id.slice(0, 8).toUpperCase()}</div>
                    <h1 className={styles.detailPageTitle}>{detail.task.title}</h1>
                    <section className={styles.taskBrief}>
                      <h3>{i18n('task.field.description')}</h3>
                      <div>{detail.task.description || i18n('task.detail.noDescription')}</div>
                    </section>
                    <section className={styles.taskBrief}>
                      <h3><ListChecks size={15} /> {i18n('task.field.acceptanceCriteria')}</h3>
                      <div>{detail.task.acceptanceCriteria || i18n('task.detail.noCriteria')}</div>
                    </section>

                    {pendingApprovals.map((approval) => (
                      <PendingApproval key={approval.id} approval={approval} detail={detail} onDecision={decide} />
                    ))}

                    <section className={styles.outputSection}>
                      <div className={styles.sectionHeading}>
                        <h2>{i18n('task.detail.results')}</h2>
                        <span>{detail.artifacts.length}</span>
                      </div>
                      {detail.artifacts.length ? (
                        detail.artifacts.map((artifact) => (
                          <ArtifactView
                            key={artifact.artifact.id}
                            detail={artifact}
                            publications={detail.dashboardPublications}
                            dataSources={dataSources}
                            onPublish={openPublish}
                            hasStructuredChartArtifact={detail.artifacts.some(
                              (item) => item.artifact.createdByRunId === artifact.artifact.createdByRunId
                                && artifactCharts(item).length > 0,
                            )}
                          />
                        ))
                      ) : (
                        <div className={styles.inlineEmpty}>{i18n('task.artifact.empty')}</div>
                      )}
                    </section>

                    <section className={styles.conversationSection}>
                      <div className={styles.sectionHeading}>
                        <h2>{i18n('task.detail.activity')}</h2>
                        <span>{conversationItems.length}</span>
                      </div>
                      <div className={styles.conversationTimeline}>
                        {conversationItems.map((item) =>
                          item.kind === 'created' ? (
                            <div className={styles.systemActivity} key={item.id}>
                              <span className={styles.activityDot} />
                              <span>{item.content}</span>
                              <time>{formatTime(item.occurredAt)}</time>
                            </div>
                          ) : (
                            <article className={styles.conversationItem} key={item.id}>
                              <AgentAvatar agent={item.kind === 'user' ? undefined : selectedAgent} size={28} />
                              <div className={styles.conversationContent}>
                                <button
                                  type="button"
                                  className={styles.conversationHeader}
                                  aria-expanded={item.kind === 'user' || expandedActivities.has(item.id)}
                                  onClick={() => {
                                    if (item.kind === 'user') return;
                                    setExpandedActivities((current) => {
                                      const next = new Set(current);
                                      if (next.has(item.id)) next.delete(item.id);
                                      else next.add(item.id);
                                      return next;
                                    });
                                  }}
                                >
                                  {item.kind !== 'user' && (
                                    <ChevronRight
                                      size={13}
                                      className={expandedActivities.has(item.id) ? styles.chevronOpen : ''}
                                    />
                                  )}
                                  <strong>
                                    {item.kind === 'user'
                                      ? i18n('task.conversation.user')
                                      : selectedAgent?.name || i18n('task.conversation.agent')}
                                  </strong>
                                  {item.contextType && item.contextType !== 'COMMENT' && (
                                    <Tag bordered={false}>
                                      {i18n(
                                        `task.context.${item.contextType.toLowerCase()}` as Parameters<typeof i18n>[0],
                                      )}
                                    </Tag>
                                  )}
                                  {item.run && <span>{i18n('task.run.round', item.run.attempt)}</span>}
                                  <time>{formatTime(item.occurredAt)}</time>
                                </button>
                                {(item.kind === 'user' || expandedActivities.has(item.id)) && (
                                  <>
                                    {item.run && buildToolActivities(detail.eventsByRunId[item.run.id] || []).map(
                                      (activity) => (
                                        <details className={styles.toolActivityCard} key={activity.id}>
                                          <summary>
                                            <TerminalSquare size={14} />
                                            <strong>{activity.name}</strong>
                                            <Tag
                                              bordered={false}
                                              color={activity.status === 'COMPLETED'
                                                ? 'success'
                                                : activity.status === 'FAILED' ? 'error' : 'processing'}
                                            >
                                              {activity.status === 'COMPLETED' && i18n('task.tool.completed')}
                                              {activity.status === 'FAILED' && i18n('task.status.failed')}
                                              {activity.status === 'RUNNING' && i18n('task.tool.running')}
                                            </Tag>
                                            <ChevronRight size={13} />
                                          </summary>
                                          {activity.arguments && (
                                            <div className={styles.toolActivityBlock}>
                                              <span>{i18n('task.tool.arguments')}</span>
                                              <pre>{activity.arguments}</pre>
                                            </div>
                                          )}
                                          {activity.result && (
                                            <div className={styles.toolActivityBlock}>
                                              <span>{i18n('task.tool.result')}</span>
                                              <pre>{activity.result}</pre>
                                            </div>
                                          )}
                                        </details>
                                      ),
                                    )}
                                    <div className={item.kind === 'error' ? styles.conversationError : styles.prose}>
                                      <ReactMarkdown remarkPlugins={[remarkGfm]}>{item.content}</ReactMarkdown>
                                    </div>
                                  </>
                                )}
                              </div>
                            </article>
                          ),
                        )}
                        {activeRun && (
                          <article className={styles.conversationItem}>
                            <AgentAvatar agent={selectedAgent} size={28} />
                            <div className={styles.agentWorking}>
                              <span className={styles.workingPulse} />
                              <span>{i18n('task.conversation.working', selectedAgent?.name || '')}</span>
                            </div>
                          </article>
                        )}
                      </div>
                    </section>
                  </div>

                  <div className={styles.messageDock}>
                    <div className={styles.messageComposer}>
                      <Mentions
                        autoSize={{ minRows: 2, maxRows: 8 }}
                        value={messageDraft}
                        maxLength={200000}
                        disabled={!!activeRun || detail.task.status === 'CANCELLED'}
                        options={agents.map((agent) => ({
                          value: agent.name,
                          label: (
                            <span className={styles.mentionOption}>
                              <AgentAvatar agent={agent} size={22} />
                              <span><strong>{agent.name}</strong><small>{agent.description}</small></span>
                              <RuntimeBadge agent={agent} compact />
                            </span>
                          ),
                        }))}
                        placeholder={
                          activeRun
                            ? i18n('task.conversation.running')
                            : i18n('task.conversation.placeholder', selectedAgent?.name || '')
                        }
                        onChange={(value) => {
                          setMessageDraft(value);
                          const mentionedAgent = agents.find((agent) => value.includes(`@${agent.name}`));
                          setMentionedAgentId(mentionedAgent?.id);
                        }}
                        onSelect={(option) => {
                          setMentionedAgentId(agents.find((agent) => agent.name === option.value)?.id);
                        }}
                        onPressEnter={(event) => {
                          if ((event.metaKey || event.ctrlKey) && !activeRun) {
                            event.preventDefault();
                            void continueConversation();
                          }
                        }}
                      />
                      <div className={styles.messageDockFooter}>
                        <div>
                          {mentionedAgentId ? (
                            <span className={styles.agentTriggerChip}>
                              <AgentAvatar agent={agentById.get(mentionedAgentId)} size={18} />
                              @{agentById.get(mentionedAgentId)?.name}
                              <button
                                type="button"
                                aria-label={i18n('task.conversation.removeAgent')}
                                onClick={() => {
                                  const name = agentById.get(mentionedAgentId)?.name;
                                  setMessageDraft((value) => (name ? value.replace(`@${name}`, '').trimStart() : value));
                                  setMentionedAgentId(undefined);
                                }}
                              >
                                <X size={12} />
                              </button>
                            </span>
                          ) : (
                            <span className={styles.mentionHint}>{i18n('task.conversation.mentionHint')}</span>
                          )}
                        </div>
                        <span>{i18n('task.conversation.shortcut')}</span>
                        <Button
                          type="primary"
                          icon={<Send size={14} />}
                          loading={submitting}
                          disabled={!messageDraft.trim() || !!activeRun || detail.task.status === 'CANCELLED'}
                          onClick={() => void continueConversation()}
                        >
                          {i18n('task.conversation.send')}
                        </Button>
                      </div>
                    </div>
                  </div>
                </div>

                <aside className={styles.taskInspector}>
                  <div className={styles.inspectorSection}>
                    <h3>{i18n('task.detail.properties')}</h3>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.status')}</span>
                      <Tag bordered={false} color={statusColor[detail.task.status]}>
                        {statusLabel(detail.task.status)}
                      </Tag>
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.agent')}</span>
                      <span className={styles.propertyAgent}>
                        <AgentAvatar agent={selectedAgent} size={20} />
                        {selectedAgent?.name || i18n('task.agent.unknown')}
                      </span>
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.agent.runtime')}</span>
                      <RuntimeBadge agent={selectedAgent} run={currentRun} />
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.priority')}</span>
                      <span>{priorityLabel(detail.task.priority)}</span>
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.origin')}</span>
                      {detail.task.originType === 'SCHEDULE' ? (
                        <Button
                          type="link"
                          size="small"
                          onClick={() => openSchedules(detail.task.originScheduleId)}
                        >
                          {i18n('task.schedule.origin')}
                        </Button>
                      ) : <span>{detail.task.originType}</span>}
                    </div>
                    {detail.task.plannedAt && (
                      <div className={styles.propertyRow}>
                        <span>{i18n('task.schedule.plannedAt')}</span>
                        <span>{formatTime(detail.task.plannedAt)}</span>
                      </div>
                    )}
                  </div>
                  <div className={styles.inspectorSection}>
                    <button
                      type="button"
                      className={styles.inspectorCollapseHeader}
                      aria-expanded={executionOpen}
                      onClick={() => setExecutionOpen((value) => !value)}
                    >
                      <h3>{i18n('task.detail.execution')}</h3>
                      <ChevronRight size={13} className={executionOpen ? styles.chevronOpen : ''} />
                      {activeRuns.length > 0 && <span className={styles.activeRunCount}>{activeRuns.length}</span>}
                    </button>
                    {executionOpen && activeRuns.map((run) => (
                      <div className={styles.runRow} key={run.id}>
                        <div><Play size={12} /><strong>{i18n('task.run.round', run.attempt)}</strong></div>
                        <RunStatusMark status={run.status} />
                        <time>{formatTime(run.startedAt || detail.task.gmtCreate)}</time>
                        {['QUEUED', 'DISPATCHED', 'RUNNING', 'WAITING_APPROVAL'].includes(run.status) && (
                          <Button size="small" type="text" danger onClick={() => void cancelRun(run)}>{i18n('task.run.cancel')}</Button>
                        )}
                      </div>
                    ))}
                    {executionOpen && pastRuns.length > 0 && (
                      <>
                        <button
                          type="button"
                          className={styles.pastRunsToggle}
                          onClick={() => setPastRunsOpen((value) => !value)}
                        >
                          <ChevronRight size={12} className={pastRunsOpen ? styles.chevronOpen : ''} />
                          {pastRunsOpen
                            ? i18n('task.run.hidePast', pastRuns.length)
                            : i18n('task.run.showPast', pastRuns.length)}
                        </button>
                        {pastRunsOpen && pastRuns.map((run) => (
                          <div className={styles.runRow} key={run.id}>
                            <div><Play size={12} /><strong>{i18n('task.run.round', run.attempt)}</strong></div>
                            <RunStatusMark status={run.status} />
                            <time>{formatTime(run.startedAt || detail.task.gmtCreate)}</time>
                          </div>
                        ))}
                      </>
                    )}
                  </div>
                  <div className={styles.inspectorSection}>
                    <div className={styles.scopeSectionTitle}>
                      <h3>{i18n('task.scope.title')}</h3>
                      {detail.task.dataScopeSyncedAt ? (
                        <Tag color="success" icon={<CircleCheck size={11} />}>
                          {i18n('task.scope.synced')}
                        </Tag>
                      ) : null}
                    </div>
                    <div className={styles.scopeSnapshotHint}>
                      {i18n('task.scope.effectiveSnapshotHint')}
                    </div>
                    {detail.task.dataScopeSnapshot.length ? (
                      <>
                        {detail.task.dataScopeSnapshot.map((scope, index) => (
                          <div className={styles.inspectorScope} key={`${scope.dataSourceId}-${index}`}>
                            <ShieldCheck size={13} />
                            <div>
                              <strong>
                                {dataSourceDisplayName(
                                  scope.dataSourceId,
                                  dataSources,
                                  i18n('task.scope.datasourceUnavailable', scope.dataSourceId),
                                )}
                              </strong>
                              <span>
                                {[scope.databaseName, scope.schemaName].filter(Boolean).join(' / ')
                                  || i18n('task.scope.all')}
                              </span>
                              <ApprovalModeTag
                                mode={scope.approvalMode}
                                className={styles.scopeApprovalTag}
                              />
                            </div>
                          </div>
                        ))}
                        <Button size="small" loading={submitting} onClick={() => void syncTaskScopes()}>
                          {detail.task.dataScopeSyncedAt
                            ? i18n('task.scope.syncAgain')
                            : i18n('task.scope.syncFromAgent')}
                        </Button>
                      </>
                    ) : (
                      <div className={styles.emptyScopeAction}>
                        <span className={styles.mutedText}>{i18n('task.scope.empty')}</span>
                        {selectedAgent?.dataScopes?.length ? (
                          <Button size="small" loading={submitting} onClick={() => void syncTaskScopes()}>
                            {i18n('task.scope.syncFromAgent')}
                          </Button>
                        ) : null}
                      </div>
                    )}
                  </div>
                  <div className={styles.inspectorSection}>
                    <h3>{i18n('task.detail.timestamps')}</h3>
                    <div className={styles.propertyRow}><span>{i18n('task.detail.createdAt')}</span><span>{formatTime(detail.task.gmtCreate)}</span></div>
                    <div className={styles.propertyRow}><span>{i18n('task.field.updatedAt')}</span><span>{formatTime(detail.task.gmtModified)}</span></div>
                    {detail.task.completedAt && <div className={styles.propertyRow}><span>{i18n('task.run.completedAt')}</span><span>{formatTime(detail.task.completedAt)}</span></div>}
                  </div>
                </aside>
              </div>
            )}
          </main>
        </>
      ) : (
        <>
          <header className={styles.header}>
        <div className={styles.titleGroup}>
          <span className={styles.titleIcon}>
            <FolderKanban size={17} />
          </span>
          <div className={styles.titleCopy}>
            <div className={styles.titleGroup}>
              <h1 className={styles.title}>
                {archiveView ? i18n('task.archive.records') : i18n('task.title')}
              </h1>
              <span className={styles.count}>
                {archiveView
                  ? archivedTasks.length
                  : hasTaskFilters
                    ? i18n('task.filter.resultCount', filteredTasks.length, tasks.length)
                    : tasks.length}
              </span>
            </div>
            <p>{archiveView ? i18n('task.archive.hint') : i18n('task.board.hint')}</p>
          </div>
        </div>
        <div className={styles.toolbar}>
          <Segmented<ViewMode>
            disabled={archiveView}
            value={viewMode}
            onChange={setViewMode}
            options={[
              { value: 'board', label: i18n('task.view.board') },
              { value: 'list', label: i18n('task.view.list') },
            ]}
          />
          <Tooltip title={archiveView ? i18n('task.archive.back') : i18n('task.archive.records')}>
            <Button
              type={archiveView ? 'primary' : 'default'}
              icon={<Archive size={15} />}
              aria-label={archiveView ? i18n('task.archive.back') : i18n('task.archive.records')}
              onClick={() => openArchive(!archiveView)}
            />
          </Tooltip>
          <Tooltip title={i18n('task.action.refresh')}>
            <Button
              icon={<RefreshCw size={15} />}
              aria-label={i18n('task.action.refresh')}
              onClick={() => void load()}
            />
          </Tooltip>
          <Tooltip title={i18n('task.schedule.title')}>
            <Button
              icon={<CalendarClock size={15} />}
              aria-label={i18n('task.schedule.title')}
              onClick={() => openSchedules()}
            />
          </Tooltip>
          <Tooltip title={i18n('task.agent.manage')}>
            <Button
              icon={<Bot size={15} />}
              aria-label={i18n('task.agent.manage')}
              onClick={openAgentManager}
            />
          </Tooltip>
          <Tooltip title={i18n('task.create.action')}>
            <Button
              type="primary"
              icon={<Plus size={15} />}
              aria-label={i18n('task.create.action')}
              onClick={openTaskCreate}
            />
          </Tooltip>
        </div>
          </header>

          <main className={`${styles.content} ${!archiveView ? styles.contentWithFilters : ''}`}>
        {!archiveView && (
          <div className={styles.taskFilters} role="search" aria-label={i18n('task.filter.title')}>
            <Input
              allowClear
              value={taskTitleFilter}
              prefix={<Search size={14} aria-hidden="true" />}
              placeholder={i18n('task.filter.titlePlaceholder')}
              onChange={(event) => setTaskTitleFilter(event.target.value)}
            />
            <Select
              mode="multiple"
              maxTagCount="responsive"
              showSearch
              optionFilterProp="label"
              value={taskAgentFilter}
              placeholder={i18n('task.filter.agentPlaceholder')}
              options={agents.map((agent) => ({ value: agent.id, label: agent.name }))}
              onChange={setTaskAgentFilter}
              optionRender={(option) => {
                const agent = agentById.get(String(option.value));
                return <AgentIdentity agent={agent} fallback={option.label} />;
              }}
              tagRender={({ value, label, closable, onClose }) => (
                <Tag
                  bordered={false}
                  closable={closable}
                  onClose={onClose}
                  onMouseDown={(event) => {
                    event.preventDefault();
                    event.stopPropagation();
                  }}
                >
                  <span className={styles.agentFilterTag}>
                    <AgentAvatar agent={agentById.get(String(value))} size={17} />
                    <span>{label}</span>
                  </span>
                </Tag>
              )}
            />
            <Select
              mode="multiple"
              maxTagCount="responsive"
              value={taskStatusFilter}
              placeholder={i18n('task.filter.statusPlaceholder')}
              options={TASK_BOARD_COLUMNS.map((column) => ({
                value: column.key,
                label: i18n(`task.column.${column.key}` as Parameters<typeof i18n>[0]),
              }))}
              onChange={setTaskStatusFilter}
            />
            {hasTaskFilters && (
              <Button type="text" onClick={clearTaskFilters}>
                {i18n('task.filter.clear')}
              </Button>
            )}
          </div>
        )}
        {archiveView ? (
          archivedTasks.length ? (
            <Table
              rowKey="id"
              size="middle"
              dataSource={archivedTasks}
              columns={[
                { title: i18n('task.field.title'), dataIndex: 'title' },
                {
                  title: i18n('task.field.status'),
                  dataIndex: 'status',
                  width: 130,
                  render: (value) => <Tag color={statusColor[value]}>{statusLabel(value)}</Tag>,
                },
                { title: i18n('task.archive.archivedAt'), dataIndex: 'archivedAt', render: formatTime },
                {
                  title: i18n('task.archive.operation'),
                  width: 130,
                  render: (_, task: AgentTask) => (
                    <Button
                      danger
                      type="text"
                      icon={<Trash2 size={14} />}
                      onClick={() => deleteArchivedTask(task)}
                    >
                      {i18n('task.archive.delete')}
                    </Button>
                  ),
                },
              ]}
            />
          ) : (
            <Empty description={i18n('task.archive.empty')} />
          )
        ) : !tasks.length ? (
          <Empty description={i18n('task.empty')}>
            <Button type="primary" onClick={openTaskCreate}>
              {i18n('task.create.first')}
            </Button>
          </Empty>
        ) : !filteredTasks.length ? (
          <Empty description={i18n('task.filter.empty')}>
            <Button onClick={clearTaskFilters}>{i18n('task.filter.clear')}</Button>
          </Empty>
        ) : viewMode === 'board' ? (
          <div className={styles.board}>
            {groupedTasks.map((group) => (
              <section className={styles.column} key={group.key}>
                <div className={styles.columnHeader}>
                  <span className={styles.columnHeading}>
                    {(() => {
                      const Icon = boardColumnIcon[group.key as keyof typeof boardColumnIcon];
                      return <Icon className={styles.columnStatusIcon} size={14} />;
                    })()}
                    {i18n(`task.column.${group.key}` as Parameters<typeof i18n>[0])}
                  </span>
                  <span className={styles.columnCount}>{group.tasks.length}</span>
                </div>
                <div className={styles.taskList}>
                  {group.tasks.length ? (
                    group.tasks.map((task) => (
                      <TaskCard
                        key={task.id}
                        task={task}
                        agent={agentById.get(task.assigneeAgentId)}
                        onOpen={() => openTask(task.id)}
                      />
                    ))
                  ) : (
                    <div className={styles.emptyColumn}>{i18n('task.column.empty')}</div>
                  )}
                </div>
              </section>
            ))}
          </div>
        ) : (
          <Table
            rowKey="id"
            size="middle"
            dataSource={filteredTasks}
            onRow={(task) => ({ onClick: () => openTask(task.id), style: { cursor: 'pointer' } })}
            columns={[
              { title: i18n('task.field.title'), dataIndex: 'title' },
              {
                title: i18n('task.field.status'),
                dataIndex: 'status',
                width: 130,
                render: (value) => <Tag color={statusColor[value]}>{statusLabel(value)}</Tag>,
              },
              {
                title: i18n('task.field.agent'),
                dataIndex: 'assigneeAgentId',
                render: (value) => agentById.get(value)?.name || i18n('task.agent.unknown'),
              },
              { title: i18n('task.field.updatedAt'), dataIndex: 'gmtModified', render: formatTime },
            ]}
          />
        )}
          </main>
        </>
      )}
      {workspaceTabs.filter((tab) => tab.type === 'TASK_CREATE').map((tab) => (
        <div key={tab.key} className={styles.taskWorkspacePane} hidden={activeWorkspaceTabKey !== tab.key}>
          <TaskCreatePage
            form={createForm}
            agents={agents}
            dataSources={dataSources}
            submitting={submitting}
            onCancel={closeTask}
            onSubmit={() => void createTask()}
            onDirtyChange={(dirty) => setWorkspaceTabDirty(tab.key, dirty)}
          />
        </div>
      ))}
      {workspaceTabs.filter((tab) => tab.type === 'SCHEDULES').map((tab) => (
        <div key={tab.key} className={styles.taskWorkspacePane} hidden={activeWorkspaceTabKey !== tab.key}>
          <TaskSchedulePage
            active={tasksPageActive && activeWorkspaceTabKey === tab.key}
            agents={agents}
            dataSources={dataSources}
            scheduleId={tab.entityId}
            createMode={!tab.entityId}
            onBack={closeSchedules}
            onCreate={() => openSchedules()}
            onSelectSchedule={openSchedules}
            onOpenTask={openTask}
            onDirtyChange={(dirty) => setWorkspaceTabDirty(tab.key, dirty)}
          />
        </div>
      ))}
      {workspaceTabs.filter((tab) => tab.type === 'AGENT_MANAGER' || tab.type === 'AGENT_EDITOR').map((tab) => (
        <div key={tab.key} className={styles.taskWorkspacePane} hidden={activeWorkspaceTabKey !== tab.key}>
          <AgentManagerPage
            active={tasksPageActive && activeWorkspaceTabKey === tab.key}
            agents={agents}
            editorAgentId={tab.type === 'AGENT_EDITOR' ? tab.entityId : undefined}
            createMode={tab.type === 'AGENT_EDITOR' && !tab.entityId}
            onOpenEditor={openAgentEditor}
            onCancelEditor={openAgentManager}
            onDirtyChange={(dirty) => setWorkspaceTabDirty(tab.key, dirty)}
            onSaved={(agent) => openAgentEditor(agent)}
            onChanged={(agent, removed) => {
              setAgents((current) => {
                if (removed) return current.filter((item) => item.id !== agent.id);
                return current.some((item) => item.id === agent.id)
                  ? current.map((item) => (item.id === agent.id ? agent : item))
                  : [agent, ...current];
              });
            }}
          />
        </div>
      ))}
      </div>


      <Modal
        title={i18n('task.artifact.publish')}
        open={!!publishTarget}
        confirmLoading={submitting}
        onCancel={() => setPublishTarget(undefined)}
        onOk={() => void publish()}
        destroyOnClose
      >
        <Form form={publishForm} layout="vertical" initialValues={{ contentMode: 'SNAPSHOT' }}>
          <Form.Item name="dashboardId" label={i18n('task.publish.dashboard')} rules={[{ required: true }]}>
            <Select
              options={dashboards
                .filter((item) => item.id != null)
                .map((item) => ({ value: item.id, label: item.name }))}
              placeholder={i18n('task.publish.dashboardPlaceholder')}
            />
          </Form.Item>
          <Form.Item name="contentMode" label={i18n('task.publish.mode')} rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'SNAPSHOT', label: i18n('task.publish.snapshot') },
                { value: 'LIVE', label: i18n('task.publish.live') },
              ]}
            />
          </Form.Item>
          <Alert type="info" showIcon message={i18n('task.publish.help')} />
        </Form>
      </Modal>
    </div>
  );
}
