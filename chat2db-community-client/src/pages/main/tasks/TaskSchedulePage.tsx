import i18n from '@/i18n';
import agentService, {
  type AgentDefinition,
  type AgentTaskSchedule,
  type AgentTaskScheduleDetail,
  type AgentTaskScheduleExecution,
  type SaveAgentTaskScheduleRequest,
} from '@/service/agent';
import feedback from '@/utils/feedback';
import type { IConnectionDetails } from '@/typings';
import {
  Alert,
  Button,
  DatePicker,
  Descriptions,
  Empty,
  Form,
  Input,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import {
  Archive,
  ArrowLeft,
  CalendarClock,
  ExternalLink,
  Pause,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  Settings2,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { AgentAvatar, AgentIdentity } from './TaskPrimitives';
import ApprovalModeTag from './ApprovalModeTag';
import { effectiveApprovalMode, normalizeApprovalMode } from './approvalMode';
import { dataSourceDisplayName } from './taskDataSource';
import { taskPriorityLevel } from './taskModel';
import { canOpenScheduledTask, cronFromScheduleValues, sameDataScope } from './taskScheduleModel';
import { useTaskScheduleStyles } from './taskScheduleStyle';

interface Props {
  agents: AgentDefinition[];
  dataSources: IConnectionDetails[];
  scheduleId?: string;
  createMode: boolean;
  onBack: () => void;
  onSelectSchedule: (scheduleId: string) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
}

interface ScheduleFormValues {
  name: string;
  taskTitle: string;
  taskDescription?: string;
  acceptanceCriteria?: string;
  assigneeAgentId: string;
  priority: number;
  scopeIndexes: number[];
  scheduleType: 'ONCE' | 'CRON';
  scheduledAt?: Dayjs;
  preset?: 'DAILY' | 'WEEKDAYS' | 'WEEKLY' | 'CUSTOM';
  time?: string;
  weekday?: number;
  cronExpression?: string;
  timezone: string;
}

const currentTimezone = new Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
const timezoneOptions = Array.from(new Set([
  currentTimezone,
  'UTC',
  'Asia/Shanghai',
  'Asia/Tokyo',
  'Asia/Seoul',
  'Europe/London',
  'America/New_York',
  'America/Los_Angeles',
])).map((value) => ({ value, label: value }));

function formatTime(value?: string | number) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : i18n('task.value.none');
}

function statusColor(status: AgentTaskSchedule['status']) {
  if (status === 'ACTIVE') return 'success';
  if (status === 'PAUSED') return 'warning';
  return 'default';
}

function executionColor(status: AgentTaskScheduleExecution['status']) {
  if (status === 'DISPATCHED') return 'success';
  if (status === 'FAILED') return 'error';
  if (status === 'SKIPPED') return 'warning';
  return 'processing';
}

function scheduleStatusLabel(status: AgentTaskSchedule['status']) {
  return i18n(`task.schedule.status.${status.toLowerCase()}` as Parameters<typeof i18n>[0]);
}

function scheduleRule(schedule: AgentTaskSchedule) {
  return schedule.scheduleType === 'ONCE'
    ? formatTime(schedule.scheduledAt)
    : `${schedule.cronExpression} / ${schedule.timezone}`;
}

function priorityLabel(priority?: number) {
  return i18n(`task.priority.${taskPriorityLevel(priority)}` as Parameters<typeof i18n>[0]);
}

export default function TaskSchedulePage({
  agents,
  dataSources,
  scheduleId,
  createMode,
  onBack,
  onSelectSchedule,
  onCreate,
  onOpenTask,
}: Props) {
  const { styles, cx } = useTaskScheduleStyles();
  const [schedules, setSchedules] = useState<AgentTaskSchedule[]>([]);
  const [selected, setSelected] = useState<AgentTaskScheduleDetail>();
  const [editing, setEditing] = useState<AgentTaskSchedule>();
  const [listLoading, setListLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState<Array<string | number>>([]);
  const [form] = Form.useForm<ScheduleFormValues>();
  const agentById = useMemo(() => new Map(agents.map((agent) => [agent.id, agent])), [agents]);
  const watchedAgentId = Form.useWatch('assigneeAgentId', form);
  const watchedType = Form.useWatch('scheduleType', form);
  const watchedPreset = Form.useWatch('preset', form);
  const editingAgent = editing ? agentById.get(editing.assigneeAgentId) : undefined;
  const editingApprovalPolicyChanged = Boolean(editing && editingAgent
    && editing.dataScopeSnapshot.some((snapshot) => {
      const current = editingAgent.dataScopes.find((scope) => sameDataScope(snapshot, scope));
      return current && normalizeApprovalMode(snapshot.approvalMode) !== normalizeApprovalMode(current.approvalMode);
    }));

  const loadSchedules = useCallback(async () => {
    setListLoading(true);
    setLoadError(false);
    try {
      setSchedules((await agentService.listTaskSchedules(undefined as void)) || []);
    } catch {
      setLoadError(true);
    } finally {
      setListLoading(false);
    }
  }, []);

  const loadDetail = useCallback(async (id: string) => {
    setDetailLoading(true);
    setLoadError(false);
    try {
      setSelected(await agentService.getTaskSchedule({ scheduleId: id }));
      setEditing(undefined);
    } catch {
      setLoadError(true);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const resetCreateForm = useCallback(() => {
    setEditing(undefined);
    setSelected(undefined);
    setPreview([]);
    form.resetFields();
    form.setFieldsValue({
      priority: 0,
      scheduleType: 'CRON',
      preset: 'DAILY',
      time: '09:00',
      weekday: 1,
      timezone: currentTimezone,
      scopeIndexes: [],
    });
  }, [form]);

  useEffect(() => {
    void loadSchedules();
  }, [loadSchedules]);

  useEffect(() => {
    if (createMode) {
      resetCreateForm();
    } else if (scheduleId) {
      void loadDetail(scheduleId);
    }
  }, [createMode, loadDetail, resetCreateForm, scheduleId]);

  const beginEdit = (schedule: AgentTaskSchedule) => {
    const agent = agentById.get(schedule.assigneeAgentId);
    setEditing(schedule);
    setPreview([]);
    form.setFieldsValue({
      name: schedule.name,
      taskTitle: schedule.taskTitle,
      taskDescription: schedule.taskDescription,
      acceptanceCriteria: schedule.acceptanceCriteria,
      assigneeAgentId: schedule.assigneeAgentId,
      priority: schedule.priority,
      scopeIndexes: (agent?.dataScopes || []).map((_, index) => index)
        .filter((index) => schedule.dataScopeSnapshot.some((scope) =>
          sameDataScope(scope, agent!.dataScopes[index]))),
      scheduleType: schedule.scheduleType,
      scheduledAt: schedule.scheduledAt ? dayjs(schedule.scheduledAt) : undefined,
      preset: 'CUSTOM',
      cronExpression: schedule.cronExpression,
      timezone: schedule.timezone,
    });
  };

  const previewCron = async () => {
    const values = await form.validateFields([
      'scheduleType', 'preset', 'time', 'weekday', 'cronExpression', 'timezone',
    ]);
    const expression = cronFromScheduleValues(values as ScheduleFormValues);
    if (!expression) return;
    const result = await agentService.previewTaskSchedule({ expression, timezone: values.timezone });
    setPreview(result.nextRuns || []);
  };

  const save = async () => {
    const values = await form.validateFields();
    const agent = agentById.get(values.assigneeAgentId);
    const payload: SaveAgentTaskScheduleRequest = {
      scheduleId: editing?.id,
      expectedRevision: editing?.revision,
      name: values.name,
      taskTitle: values.taskTitle,
      taskDescription: values.taskDescription,
      acceptanceCriteria: values.acceptanceCriteria,
      assigneeAgentId: values.assigneeAgentId,
      priority: values.priority || 0,
      dataScopeSnapshot: agent
        ? (values.scopeIndexes || []).map((index) => agent.dataScopes[index]).filter(Boolean)
        : [],
      scheduleType: values.scheduleType,
      scheduledAt: values.scheduleType === 'ONCE' ? values.scheduledAt?.toISOString() : undefined,
      cronExpression: cronFromScheduleValues(values),
      timezone: values.timezone,
    };
    setSaving(true);
    try {
      const detail = editing
        ? await agentService.updateTaskSchedule(payload)
        : await agentService.createTaskSchedule(payload);
      setSelected(detail);
      setEditing(undefined);
      feedback.success(editing ? i18n('task.schedule.updated') : i18n('task.schedule.created'));
      await loadSchedules();
      onSelectSchedule(detail.schedule.id);
    } finally {
      setSaving(false);
    }
  };

  const refreshSelected = async (id: string) => {
    await Promise.all([loadSchedules(), loadDetail(id)]);
  };

  const changeStatus = async (schedule: AgentTaskSchedule, action: 'pause' | 'resume' | 'archive') => {
    setDetailLoading(true);
    try {
      if (action === 'pause') {
        await agentService.pauseTaskSchedule({ scheduleId: schedule.id, expectedRevision: schedule.revision });
      } else if (action === 'resume') {
        await agentService.resumeTaskSchedule({ scheduleId: schedule.id, expectedRevision: schedule.revision });
      } else {
        await agentService.archiveTaskSchedule({ scheduleId: schedule.id, expectedRevision: schedule.revision });
      }
      await refreshSelected(schedule.id);
    } finally {
      setDetailLoading(false);
    }
  };

  const runNow = async (schedule: AgentTaskSchedule) => {
    setDetailLoading(true);
    try {
      const execution = await agentService.runTaskScheduleNow({ scheduleId: schedule.id });
      feedback.success(execution.status === 'SKIPPED'
        ? i18n('task.schedule.runSkipped') : i18n('task.schedule.runCreated'));
      await refreshSelected(schedule.id);
    } finally {
      setDetailLoading(false);
    }
  };

  const renderForm = () => (
    <div className={styles.mainInner}>
      <div className={styles.sectionHeader}>
        <div>
          <h2>{editing ? i18n('task.schedule.edit') : i18n('task.schedule.create')}</h2>
          <p>{i18n('task.schedule.formHint')}</p>
        </div>
      </div>
      <Alert className={styles.notice} type="info" showIcon message={i18n('task.schedule.offlineNotice')} />
      {editingApprovalPolicyChanged && (
        <Alert
          className={styles.notice}
          type="warning"
          showIcon
          message={i18n('task.schedule.approvalPolicyChanged')}
          description={i18n('task.schedule.approvalPolicyEditNotice')}
        />
      )}
      <Form form={form} layout="vertical" className={styles.form}>
        <div className={styles.formGrid}>
          <Form.Item name="name" label={i18n('task.schedule.name')} rules={[{ required: true, max: 128 }]}>
            <Input autoFocus />
          </Form.Item>
          <Form.Item name="taskTitle" label={i18n('task.field.title')} rules={[{ required: true, max: 256 }]}>
            <Input />
          </Form.Item>
        </div>
        <Form.Item name="taskDescription" label={i18n('task.field.description')}>
          <Input.TextArea rows={3} />
        </Form.Item>
        <div className={styles.formGrid}>
          <Form.Item name="assigneeAgentId" label={i18n('task.field.agent')} rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={agents.filter((agent) => agent.status === 'ACTIVE')
                .map((agent) => ({ value: agent.id, label: agent.name }))}
              optionRender={(option) => (
                <AgentIdentity agent={agentById.get(String(option.value))} fallback={option.label} />
              )}
              labelRender={({ value, label }) => (
                <AgentIdentity agent={agentById.get(String(value))} fallback={label} />
              )}
              onChange={(id) => form.setFieldValue(
                'scopeIndexes',
                (agentById.get(id)?.dataScopes || []).map((_, index) => index),
              )}
            />
          </Form.Item>
          <Form.Item name="priority" label={i18n('task.field.priority')}>
            <Select
              options={[0, 10, 20, 30].map((value) => ({
                value,
                label: i18n(`task.priority.${value}` as Parameters<typeof i18n>[0]),
              }))}
            />
          </Form.Item>
        </div>
        <Form.Item name="scopeIndexes" label={i18n('task.scope.select')}>
          <Select
            mode="multiple"
            options={(agentById.get(watchedAgentId)?.dataScopes || []).map((scope, index) => ({
              value: index,
              label: `${dataSourceDisplayName(
                scope.dataSourceId,
                dataSources,
                i18n('task.scope.datasourceUnavailable', scope.dataSourceId),
              )} / ${scope.databaseName || '*'} / ${scope.schemaName || '*'} · ${i18n(
                'task.scope.approvalShort', scope.approvalMode || 'RISK_BASED',
              )}`,
            }))}
          />
        </Form.Item>
        <div className={styles.formGrid}>
          <Form.Item name="scheduleType" label={i18n('task.schedule.type')} rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'ONCE', label: i18n('task.schedule.once') },
                { value: 'CRON', label: i18n('task.schedule.recurring') },
              ]}
            />
          </Form.Item>
          <Form.Item name="timezone" label={i18n('task.schedule.timezone')} rules={[{ required: true }]}>
            <Select showSearch options={timezoneOptions} />
          </Form.Item>
        </div>
        {watchedType === 'ONCE' ? (
          <Form.Item name="scheduledAt" label={i18n('task.schedule.scheduledAt')} rules={[{ required: true }]}>
            <DatePicker showTime style={{ width: '100%' }} disabledDate={(date) => date < dayjs().startOf('day')} />
          </Form.Item>
        ) : (
          <>
            <div className={styles.formGrid}>
              <Form.Item name="preset" label={i18n('task.schedule.pattern')} rules={[{ required: true }]}>
                <Select
                  options={[
                    { value: 'DAILY', label: i18n('task.schedule.daily') },
                    { value: 'WEEKDAYS', label: i18n('task.schedule.weekdays') },
                    { value: 'WEEKLY', label: i18n('task.schedule.weekly') },
                    { value: 'CUSTOM', label: i18n('task.schedule.custom') },
                  ]}
                />
              </Form.Item>
              {watchedPreset === 'CUSTOM' ? (
                <Form.Item name="cronExpression" label={i18n('task.schedule.cron')} rules={[{ required: true }]}>
                  <Input placeholder="0 9 * * 1-5" />
                </Form.Item>
              ) : (
                <Form.Item name="time" label={i18n('task.schedule.time')} rules={[{ required: true }]}>
                  <Input type="time" />
                </Form.Item>
              )}
            </div>
            {watchedPreset === 'WEEKLY' && (
              <Form.Item name="weekday" label={i18n('task.schedule.weekday')} rules={[{ required: true }]}>
                <Select
                  options={[1, 2, 3, 4, 5, 6, 0].map((value) => ({
                    value,
                    label: i18n(`task.schedule.weekday.${value}` as Parameters<typeof i18n>[0]),
                  }))}
                />
              </Form.Item>
            )}
            <Button onClick={() => void previewCron()}>{i18n('task.schedule.preview')}</Button>
            {preview.length > 0 && (
              <div className={styles.preview}>
                {preview.map((value) => <div key={String(value)}>{formatTime(value)}</div>)}
              </div>
            )}
          </>
        )}
        <Form.Item name="acceptanceCriteria" label={i18n('task.field.acceptanceCriteria')} style={{ marginTop: 18 }}>
          <Input.TextArea rows={2} />
        </Form.Item>
        <Alert type="warning" showIcon message={i18n('task.schedule.skipPolicy')} />
        <div className={styles.formActions}>
          {editing && (
            <Button onClick={() => setEditing(undefined)}>{i18n('task.schedule.cancel')}</Button>
          )}
          <Button type="primary" icon={<Save size={14} />} loading={saving} onClick={() => void save()}>
            {i18n('task.schedule.save')}
          </Button>
        </div>
      </Form>
    </div>
  );

  const renderDetail = (detail: AgentTaskScheduleDetail) => {
    const schedule = detail.schedule;
    const agent = agentById.get(schedule.assigneeAgentId);
    return (
      <div className={styles.mainInner}>
        <div className={styles.sectionHeader}>
          <div>
            <h2>{schedule.name}</h2>
            <p>{schedule.taskTitle}</p>
          </div>
          <div className={styles.sectionActions}>
            <Button icon={<Play size={14} />} disabled={schedule.status === 'ARCHIVED'} onClick={() => void runNow(schedule)}>
              {i18n('task.schedule.runNow')}
            </Button>
            <Button icon={<Settings2 size={14} />} disabled={schedule.status === 'ARCHIVED'} onClick={() => beginEdit(schedule)}>
              {i18n('task.schedule.edit')}
            </Button>
            {schedule.status === 'ACTIVE' ? (
              <Button icon={<Pause size={14} />} onClick={() => void changeStatus(schedule, 'pause')}>
                {i18n('task.schedule.pause')}
              </Button>
            ) : schedule.status === 'PAUSED' ? (
              <Button icon={<RotateCcw size={14} />} onClick={() => void changeStatus(schedule, 'resume')}>
                {i18n('task.schedule.resume')}
              </Button>
            ) : null}
            {schedule.status !== 'ARCHIVED' && (
              <Button danger icon={<Archive size={14} />} onClick={() => void changeStatus(schedule, 'archive')}>
                {i18n('task.schedule.archive')}
              </Button>
            )}
          </div>
        </div>
        <Descriptions className={styles.detailSummary} bordered size="small" column={{ xs: 1, sm: 1, md: 2 }}>
          <Descriptions.Item label={i18n('task.field.status')}>
            <Tag color={statusColor(schedule.status)}>{scheduleStatusLabel(schedule.status)}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label={i18n('task.field.agent')}>
            <Space size={7}>
              <AgentAvatar agent={agent} size={20} />
              <span>{agent?.name || i18n('task.agent.unknown')}</span>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label={i18n('task.schedule.rule')}>{scheduleRule(schedule)}</Descriptions.Item>
          <Descriptions.Item label={i18n('task.schedule.timezone')}>{schedule.timezone}</Descriptions.Item>
          <Descriptions.Item label={i18n('task.schedule.nextRun')}>{formatTime(schedule.nextRunAt)}</Descriptions.Item>
          <Descriptions.Item label={i18n('task.schedule.lastRun')}>{formatTime(schedule.lastRunAt)}</Descriptions.Item>
          <Descriptions.Item label={i18n('task.field.priority')}>
            {priorityLabel(schedule.priority)}
          </Descriptions.Item>
          <Descriptions.Item label={i18n('task.scope.title')}>
            {schedule.dataScopeSnapshot.length ? (
              <Space direction="vertical" size={2}>
                {schedule.dataScopeSnapshot.map((scope, index) => {
                  const currentScope = agent?.dataScopes.find((current) => sameDataScope(scope, current));
                  const snapshotMode = normalizeApprovalMode(scope.approvalMode);
                  const effectiveMode = currentScope
                    ? effectiveApprovalMode(scope.approvalMode, currentScope.approvalMode)
                    : snapshotMode;
                  return (
                    <div
                      className={styles.scopeSummary}
                      key={`${scope.dataSourceId}-${scope.databaseName}-${scope.schemaName}-${index}`}
                    >
                      <span>
                        {dataSourceDisplayName(
                          scope.dataSourceId,
                          dataSources,
                          i18n('task.scope.datasourceUnavailable', scope.dataSourceId),
                        )}
                        {' · '}
                        {[scope.databaseName || '*', scope.schemaName || '*'].join(' / ')}
                      </span>
                      <ApprovalModeTag
                        mode={effectiveMode}
                        label={i18n('task.scope.effectiveApprovalBadge', effectiveMode)}
                      />
                      {snapshotMode !== effectiveMode && (
                        <small>{i18n('task.schedule.approvalPolicyDrift', snapshotMode, effectiveMode)}</small>
                      )}
                    </div>
                  );
                })}
              </Space>
            ) : i18n('task.scope.empty')}
          </Descriptions.Item>
        </Descriptions>
        {schedule.taskDescription && (
          <div className={styles.textBlock}>
            <h3>{i18n('task.field.description')}</h3>
            <p>{schedule.taskDescription}</p>
          </div>
        )}
        {schedule.acceptanceCriteria && (
          <div className={styles.textBlock}>
            <h3>{i18n('task.field.acceptanceCriteria')}</h3>
            <p>{schedule.acceptanceCriteria}</p>
          </div>
        )}
        <section className={styles.history}>
          <div className={styles.historyHeader}>
            <h3>{i18n('task.schedule.history')}</h3>
            <Button type="text" icon={<RefreshCw size={14} />} onClick={() => void refreshSelected(schedule.id)}>
              {i18n('task.schedule.refresh')}
            </Button>
          </div>
          <Table
            rowKey="id"
            size="small"
            pagination={{ pageSize: 8, hideOnSinglePage: true }}
            dataSource={detail.executions}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={i18n('task.schedule.executionEmpty')} /> }}
            columns={[
              { title: i18n('task.schedule.plannedAt'), dataIndex: 'plannedAt', width: 150, render: formatTime },
              { title: i18n('task.schedule.source'), dataIndex: 'source', width: 90 },
              {
                title: i18n('task.field.status'),
                dataIndex: 'status',
                width: 180,
                render: (status, execution: AgentTaskScheduleExecution) => (
                  <Space size={4} wrap>
                    <Tag color={executionColor(status)}>{status}</Tag>
                    {execution.runStatus && <Tag>{execution.runStatus}</Tag>}
                  </Space>
                ),
              },
              {
                title: i18n('task.schedule.result'),
                render: (_: unknown, execution: AgentTaskScheduleExecution) => execution.taskId
                  ? canOpenScheduledTask(execution.taskId, execution.taskLinkState)
                    ? (
                      <Button type="link" icon={<ExternalLink size={13} />} onClick={() => onOpenTask(execution.taskId!)}>
                        {execution.taskLinkState === 'ARCHIVED'
                          ? i18n('task.schedule.taskArchived') : i18n('task.schedule.viewTask')}
                      </Button>
                    )
                    : execution.taskLinkState === 'ARCHIVED'
                      ? i18n('task.schedule.taskArchived')
                      : i18n('task.schedule.taskDeleted')
                  : execution.runFailureReason || execution.failureReason
                    || execution.reasonCode || execution.resultSummary || '-',
              },
            ]}
          />
        </section>
      </div>
    );
  };

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div className={styles.headerIdentity}>
          <Button type="text" icon={<ArrowLeft size={16} />} onClick={onBack}>
            {i18n('task.schedule.backToTasks')}
          </Button>
          <div className={styles.headerTitle}>
            <h1>{i18n('task.schedule.title')}</h1>
            <p>{i18n('task.schedule.pageHint')}</p>
          </div>
        </div>
        <Space>
          <Button icon={<RefreshCw size={14} />} loading={listLoading} onClick={() => void loadSchedules()}>
            {i18n('task.schedule.refresh')}
          </Button>
          <Button type="primary" icon={<Plus size={14} />} onClick={onCreate}>
            {i18n('task.schedule.create')}
          </Button>
        </Space>
      </header>
      <div className={styles.workspace}>
        <aside className={styles.sidebar}>
          <div className={styles.sidebarHeader}>
            <div>
              <strong>{i18n('task.schedule.listTitle')}</strong>
              <span>{schedules.length}</span>
            </div>
          </div>
          {listLoading ? (
            <div className={styles.scheduleList}><Skeleton active paragraph={{ rows: 8 }} /></div>
          ) : loadError && !schedules.length ? (
            <div className={styles.sidebarEmpty}>
              <Alert
                type="error"
                showIcon
                message={i18n('task.schedule.loadFailed')}
                action={(
                  <Button size="small" onClick={() => void loadSchedules()}>
                    {i18n('task.action.retry')}
                  </Button>
                )}
              />
            </div>
          ) : schedules.length ? (
            <div className={styles.scheduleList}>
              {schedules.map((schedule) => {
                const agent = agentById.get(schedule.assigneeAgentId);
                return (
                  <button
                    type="button"
                    key={schedule.id}
                    className={cx(
                      styles.scheduleItem,
                      scheduleId === schedule.id && !createMode && styles.scheduleItemSelected,
                    )}
                    onClick={() => onSelectSchedule(schedule.id)}
                  >
                    <div className={styles.scheduleItemTop}>
                      <strong>{schedule.name}</strong>
                      <Tag bordered={false} color={statusColor(schedule.status)}>
                        {scheduleStatusLabel(schedule.status)}
                      </Tag>
                    </div>
                    <div className={styles.scheduleItemMeta}>
                      <AgentAvatar agent={agent} size={20} />
                      <span>{agent?.name || i18n('task.agent.unknown')}</span>
                      <span>{scheduleRule(schedule)}</span>
                    </div>
                    <div className={styles.scheduleItemNext}>
                      {i18n('task.schedule.nextRun')}: {formatTime(schedule.nextRunAt)}
                    </div>
                  </button>
                );
              })}
            </div>
          ) : (
            <div className={styles.sidebarEmpty}>
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={i18n('task.schedule.empty')}>
                <Button type="primary" size="small" onClick={onCreate}>{i18n('task.schedule.create')}</Button>
              </Empty>
            </div>
          )}
        </aside>
        <main className={styles.main}>
          {createMode || editing ? renderForm() : detailLoading ? (
            <div className={styles.mainInner}><Skeleton active paragraph={{ rows: 12 }} /></div>
          ) : selected ? renderDetail(selected) : loadError ? (
            <div className={styles.emptyMain}>
              <Alert
                type="error"
                showIcon
                message={i18n('task.schedule.loadFailed')}
                action={scheduleId
                  ? <Button onClick={() => void loadDetail(scheduleId)}>{i18n('task.action.retry')}</Button>
                  : undefined}
              />
            </div>
          ) : (
            <div className={styles.emptyMain}>
              <Empty description={i18n('task.schedule.selectHint')}>
                <Button type="primary" icon={<CalendarClock size={14} />} onClick={onCreate}>
                  {i18n('task.schedule.create')}
                </Button>
              </Empty>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
