import ChartCardBox from '@/blocks/BI/ChartCardBox';
import { ChartType, LineType, OrderByRule, OrderByType } from '@/blocks/BI/Chart/constants';
import type { ChartSchema } from '@/blocks/BI/Chart/typings';
import { TableDataType } from '@/constants/table';
import i18n from '@/i18n';
import { listAIModelConfigs } from '@/service/aiModelConfig';
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
import { getDashboardList } from '@/service/dashboard';
import type { IChartItem, IDashboardItem } from '@/typings';
import feedback from '@/utils/feedback';
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
  ChevronRight,
  CircleCheck,
  CircleDot,
  CircleEllipsis,
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
  Send,
  Settings2,
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
  TASK_TRANSITIONS,
} from './taskModel';
import { useStyles } from './style';
import AgentManagerModal from './AgentManagerModal';
import { AgentAvatar, RunStatusMark, RuntimeBadge } from './TaskPrimitives';

type ViewMode = 'board' | 'list';

const statusColor: Record<AgentTaskStatus, string> = {
  BACKLOG: 'default',
  TODO: 'blue',
  IN_PROGRESS: 'processing',
  IN_REVIEW: 'purple',
  BLOCKED: 'error',
  DONE: 'success',
  CANCELLED: 'default',
};

const boardColumnIcon = {
  backlog: CircleEllipsis,
  active: CircleDot,
  review: CirclePause,
  complete: CircleCheck,
};

function statusLabel(status: string) {
  const key = `task.status.${status.toLowerCase()}` as Parameters<typeof i18n>[0];
  return i18n(key);
}

function formatTime(value?: string | number) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : i18n('task.value.none');
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
          {task.priority}
        </span>
      </div>
    </button>
  );
}

interface ArtifactViewProps {
  detail: AgentArtifactDetail;
  publications: AgentTaskDetail['dashboardPublications'];
  onPublish: (artifact: AgentArtifactDetail, chartIndex: number) => void;
  hasStructuredChartArtifact: boolean;
}

function ArtifactView({ detail, publications, onPublish, hasStructuredChartArtifact }: ArtifactViewProps) {
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
                  { key: 'datasource', label: i18n('task.evidence.datasource'), children: evidence.dataSourceId },
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
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [archivedTasks, setArchivedTasks] = useState<AgentTask[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>('board');
  const [archiveView, setArchiveView] = useState(false);
  const [selectedTaskId, setSelectedTaskId] = useState<string>();
  const [detail, setDetail] = useState<AgentTaskDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const detailRequestTaskId = useRef<string>();
  const [createOpen, setCreateOpen] = useState(false);
  const [agentManagerOpen, setAgentManagerOpen] = useState(false);
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

  const agentById = useMemo(() => new Map(agents.map((agent) => [agent.id, agent])), [agents]);
  const groupedTasks = useMemo(() => groupTasks(tasks), [tasks]);

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
      setTasks((current) => current.map((task) => (task.id === result.task.id ? result.task : task)));
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
    const routePath = window.location.hash ? window.location.hash.replace(/^#/, '') : window.location.pathname;
    const path = routePath.split('/');
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
      void loadDetail(path[2]);
      return;
    }
    if (path[1] === 'tasks' && !path[2] && archiveView) setArchiveView(false);
  });

  const openTask = useCallback(
    (taskId: string, initialDetail?: AgentTaskDetail) => {
      setSelectedTaskId(taskId);
      if (initialDetail) {
        setDetail(initialDetail);
        setDetailLoading(false);
      }
      const url = new URL(window.location.href);
      if (url.hash) {
        url.hash = `/tasks/${taskId}`;
      } else {
        url.pathname = `/tasks/${taskId}`;
      }
      window.history.pushState({}, '', url.toString());
      if (!initialDetail) {
        void loadDetail(taskId);
      }
    },
    [loadDetail],
  );

  const closeTask = useCallback(() => {
    setSelectedTaskId(undefined);
    setDetail(undefined);
    const url = new URL(window.location.href);
    if (url.hash) {
      url.hash = '/tasks';
    } else {
      url.pathname = '/tasks';
    }
    window.history.pushState({}, '', url.toString());
  }, []);

  const openArchive = useCallback((open: boolean) => {
    setArchiveView(open);
    setSelectedTaskId(undefined);
    setDetail(undefined);
    const url = new URL(window.location.href);
    const path = open ? '/tasks/archive' : '/tasks';
    if (url.hash) {
      url.hash = path;
    } else {
      url.pathname = path;
    }
    window.history.pushState({}, '', url.toString());
  }, []);

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
      setCreateOpen(false);
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
    if (!selectedTaskId || !activeRun) return undefined;
    const timer = window.setInterval(() => void loadDetail(selectedTaskId, true), 2000);
    return () => window.clearInterval(timer);
  }, [activeRun, loadDetail, selectedTaskId]);

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
                          <strong>{i18n('task.scope.datasource', scope.dataSourceId)}</strong>
                          <div>
                            {[scope.databaseName, scope.schemaName].filter(Boolean).join(' / ') ||
                              i18n('task.scope.all')}
                          </div>
                          <div>
                            {scope.tableNames.length
                              ? i18n('task.scope.tableCount', scope.tableNames.length)
                              : i18n('task.scope.namespaceWide')}
                          </div>
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
                      <span>{detail.task.priority}</span>
                    </div>
                    <div className={styles.propertyRow}>
                      <span>{i18n('task.field.origin')}</span>
                      <span>{detail.task.originType}</span>
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
      {selectedTaskId ? (
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
                    <div className={styles.propertyRow}><span>{i18n('task.field.priority')}</span><span>{detail.task.priority}</span></div>
                    <div className={styles.propertyRow}><span>{i18n('task.field.origin')}</span><span>{detail.task.originType}</span></div>
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
                    {detail.task.dataScopeSnapshot.length ? (
                      <>
                        {detail.task.dataScopeSnapshot.map((scope, index) => (
                          <div className={styles.inspectorScope} key={`${scope.dataSourceId}-${index}`}>
                            <ShieldCheck size={13} />
                            <div>
                              <strong>{i18n('task.scope.datasource', scope.dataSourceId)}</strong>
                              <span>
                                {[scope.databaseName, scope.schemaName].filter(Boolean).join(' / ')
                                  || i18n('task.scope.all')}
                              </span>
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
              <span className={styles.count}>{archiveView ? archivedTasks.length : tasks.length}</span>
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
          <Button
            type={archiveView ? 'primary' : 'default'}
            icon={<Archive size={15} />}
            onClick={() => openArchive(!archiveView)}
          >
            {archiveView ? i18n('task.archive.back') : i18n('task.archive.records')}
            {!archiveView && archivedTasks.length ? ` (${archivedTasks.length})` : ''}
          </Button>
          <Tooltip title={i18n('task.action.refresh')}>
            <Button icon={<RefreshCw size={15} />} onClick={() => void load()} />
          </Tooltip>
          <Button icon={<Settings2 size={15} />} onClick={() => setAgentManagerOpen(true)}>
            {i18n('task.agent.manage')}
          </Button>
          <Button type="primary" icon={<Plus size={15} />} onClick={() => setCreateOpen(true)}>
            {i18n('task.create.action')}
          </Button>
        </div>
          </header>

          <main className={styles.content}>
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
            <Button type="primary" onClick={() => setCreateOpen(true)}>
              {i18n('task.create.first')}
            </Button>
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
            dataSource={tasks}
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

      <Modal
        width={720}
        title={null}
        open={createOpen}
        confirmLoading={submitting}
        onCancel={() => setCreateOpen(false)}
        onOk={() => void createTask()}
        destroyOnClose
      >
        <div className={styles.taskCreateHeader}>
          <div>
            <span>{i18n('task.title')}</span>
            <h2>{i18n('task.create.title')}</h2>
          </div>
        </div>
        <Form
          form={createForm}
          layout="vertical"
          className={styles.taskCreateForm}
          initialValues={{ priority: 0, scopeIndexes: [] }}
        >
          <Form.Item name="title" rules={[{ required: true, max: 256 }]}>
            <Input
              variant="borderless"
              autoFocus
              className={styles.taskTitleInput}
              placeholder={i18n('task.create.titlePlaceholder')}
            />
          </Form.Item>
          <Form.Item name="description">
            <Input.TextArea variant="borderless" rows={5} placeholder={i18n('task.create.descriptionPlaceholder')} />
          </Form.Item>
          <div className={styles.taskPropertyBar}>
            <Form.Item name="assigneeAgentId" label={null} rules={[{ required: true }]}>
              <Select
                style={{ minWidth: 180 }}
                options={agents.map((agent) => ({ value: agent.id, label: agent.name }))}
                onChange={(agentId) => {
                  const nextAgent = agentById.get(agentId);
                  createForm.setFieldValue('scopeIndexes', (nextAgent?.dataScopes || []).map((_, index) => index));
                }}
                placeholder={i18n('task.agent.select')}
              />
            </Form.Item>
            <Form.Item name="priority" label={null}>
              <Select
                style={{ width: 130 }}
                options={[0, 10, 20, 30].map((value) => ({
                  value,
                  label: i18n(`task.priority.${value}` as Parameters<typeof i18n>[0]),
                }))}
              />
            </Form.Item>
          </div>
          <Form.Item noStyle shouldUpdate={(before, after) => before.assigneeAgentId !== after.assigneeAgentId}>
            {({ getFieldValue }) => {
              const formAgent = agentById.get(getFieldValue('assigneeAgentId'));
              return (
                <Form.Item name="scopeIndexes" label={i18n('task.scope.select')}>
                  <Select
                    mode="multiple"
                    options={(formAgent?.dataScopes || []).map((scope, index) => ({
                      value: index,
                      label: `${scope.dataSourceId} / ${scope.databaseName || '*'} / ${scope.schemaName || '*'}`,
                    }))}
                    placeholder={i18n('task.scope.selectPlaceholder')}
                  />
                </Form.Item>
              );
            }}
          </Form.Item>
          <Form.Item name="acceptanceCriteria" label={i18n('task.field.acceptanceCriteria')}>
            <Input.TextArea rows={2} placeholder={i18n('task.create.criteriaPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>

      <AgentManagerModal
        open={agentManagerOpen}
        agents={agents}
        onClose={() => setAgentManagerOpen(false)}
        onChanged={(agent, removed) => {
          setAgents((current) => {
            if (removed) return current.filter((item) => item.id !== agent.id);
            return current.some((item) => item.id === agent.id)
              ? current.map((item) => (item.id === agent.id ? agent : item))
              : [agent, ...current];
          });
        }}
      />

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
