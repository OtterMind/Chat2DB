import createRequest from './base';
import type { IChatAttachment } from './aiAttachment';
import type { IChatMessage } from './aiStream';

export type AgentTaskStatus = 'BACKLOG' | 'TODO' | 'IN_PROGRESS' | 'WAITING_APPROVAL' | 'IN_REVIEW' | 'BLOCKED' | 'DONE' | 'CANCELLED';

export type AgentRunStatus =
  | 'QUEUED'
  | 'DISPATCHED'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'UNKNOWN';

export type AgentArtifactType = 'REPORT' | 'METRIC' | 'CHART' | 'DATA_TABLE' | 'FILE';
export type AgentArtifactContentMode = 'SNAPSHOT' | 'LIVE';

export interface AgentDataScope {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  tableNames: string[];
  excludedTableNames: string[];
  maxRows?: number;
  timeoutSeconds?: number;
  approvalMode?: 'NEVER' | 'RISK_BASED' | 'ALWAYS';
  allowProduction?: boolean;
}

export interface AgentDataWikiBinding {
  dataWikiId: string;
  maxRows: number;
  timeoutSeconds: number;
  approvalMode: 'NEVER' | 'RISK_BASED' | 'ALWAYS';
  allowProduction: boolean;
}

export interface AgentDefinition {
  id: string;
  name: string;
  avatar?: string;
  description?: string;
  status: 'ACTIVE' | 'DISABLED' | 'ARCHIVED';
  runtimeType: 'EMBEDDED_SPRING_AI' | 'EXTERNAL_AGENT';
  runtimeProfileId?: string;
  modelConfigId?: string;
  systemPrompt?: string;
  capabilities: string[];
  dataScopes: AgentDataScope[];
  dataWikiIds: string[];
  dataWikiBindings: AgentDataWikiBinding[];
  effectiveDataScopes: AgentDataScope[];
  outputContract?: string;
  revision: number;
}

export function agentEffectiveDataScopes(agent?: AgentDefinition): AgentDataScope[] {
  return agent?.effectiveDataScopes || agent?.dataScopes || [];
}

export type AgentRuntimeProvider = 'CLAUDE_CODE' | 'CODEX' | 'OPENCODE' | 'PI' | 'HERMES' | 'DSH';

export interface AgentRuntimeOption {
  profileId: string;
  profileName: string;
  provider: AgentRuntimeProvider;
  executable: string;
  defaultProfile: boolean;
  installed: boolean;
  online: boolean;
  status?: 'ONLINE' | 'OFFLINE' | 'DEGRADED' | 'DISABLED';
  providerVersion?: string;
  daemonId?: string;
  activeRuns?: number;
  maxConcurrency?: number;
}

export interface AgentTask {
  id: string;
  title: string;
  description?: string;
  acceptanceCriteria?: string;
  status: AgentTaskStatus;
  priority: number;
  assigneeAgentId: string;
  originType: 'CHAT' | 'BOARD' | 'CONSOLE' | 'API' | 'SCHEDULE' | 'CONNECTOR';
  originSessionId?: string;
  originMessageId?: string;
  originScheduleId?: string;
  originScheduleExecutionId?: string;
  plannedAt?: string | number;
  dataScopeSnapshot: AgentDataScope[];
  dataScopeSyncedAt?: string | number;
  dataScopeSyncedFromAgentRevision?: number;
  currentRunId?: string;
  gmtCreate: string | number;
  gmtModified: string | number;
  completedAt?: string | number;
  archivedAt?: string | number;
  revision: number;
}

export type AgentTaskScheduleType = 'ONCE' | 'CRON';
export type AgentTaskScheduleStatus = 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
export type AgentTaskScheduleExecutionStatus = 'CLAIMED' | 'TASK_CREATED' | 'DISPATCHED' | 'SKIPPED' | 'FAILED';

export interface AgentTaskSchedule {
  id: string;
  name: string;
  taskTitle: string;
  taskDescription?: string;
  acceptanceCriteria?: string;
  assigneeAgentId: string;
  priority: number;
  dataScopeSnapshot: AgentDataScope[];
  scheduleType: AgentTaskScheduleType;
  scheduledAt?: string | number;
  cronExpression?: string;
  timezone: string;
  status: AgentTaskScheduleStatus;
  concurrencyPolicy: 'SKIP';
  catchUpPolicy: 'LATEST_ONLY';
  nextRunAt?: string | number;
  lastRunAt?: string | number;
  createdBy: number;
  gmtCreate: string | number;
  gmtModified: string | number;
  revision: number;
}

export interface AgentTaskScheduleExecution {
  id: string;
  scheduleId: string;
  source: 'SCHEDULE' | 'MANUAL';
  plannedAt: string | number;
  status: AgentTaskScheduleExecutionStatus;
  taskId?: string;
  runId?: string;
  attempt: number;
  reasonCode?: string;
  failureReason?: string;
  taskLinkState?: 'AVAILABLE' | 'ARCHIVED' | 'DELETED';
  taskStatus?: AgentTaskStatus;
  runStatus?: AgentRunStatus;
  runFailureReason?: string;
  resultSummary?: string;
  gmtCreate: string | number;
  gmtModified: string | number;
  revision: number;
}

export interface AgentTaskScheduleDetail {
  schedule: AgentTaskSchedule;
  executions: AgentTaskScheduleExecution[];
}

export interface SaveAgentTaskScheduleRequest {
  scheduleId?: string;
  expectedRevision?: number;
  name: string;
  taskTitle: string;
  taskDescription?: string;
  acceptanceCriteria?: string;
  assigneeAgentId: string;
  priority: number;
  dataScopeSnapshot: AgentDataScope[];
  scheduleType: AgentTaskScheduleType;
  scheduledAt?: string;
  cronExpression?: string;
  timezone: string;
}

export interface AgentRun {
  id: string;
  taskId: string;
  agentId: string;
  runtimeType: 'EMBEDDED_SPRING_AI' | 'EXTERNAL_AGENT';
  triggerType: string;
  status: AgentRunStatus;
  attempt: number;
  startedAt?: string | number;
  completedAt?: string | number;
  failureReason?: string;
  resultSummary?: string;
  revision: number;
}

export interface AgentRunEvent {
  sequence: number;
  eventId: string;
  runId: string;
  type: string;
  content?: string;
  payload: Record<string, unknown>;
  occurredAt: string | number;
}

export interface AgentArtifact {
  id: string;
  taskId: string;
  type: AgentArtifactType;
  title: string;
  status: 'DRAFT' | 'READY' | 'ARCHIVED';
  currentVersion: number;
  createdByRunId?: string;
  revision: number;
}

export interface AgentArtifactVersion {
  artifactId: string;
  version: number;
  contentMode: AgentArtifactContentMode;
  content: Record<string, unknown>;
  contentHash: string;
  createdByRunId?: string;
  createdAt: string | number;
}

export interface AgentArtifactEvidence {
  id: string;
  artifactId: string;
  artifactVersion: number;
  runId: string;
  toolAttemptId: string;
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  sqlSnapshot: string;
  executedAt?: string | number;
  rowCount?: number;
}

export interface AgentArtifactDetail {
  artifact: AgentArtifact;
  versions: AgentArtifactVersion[];
  evidence: AgentArtifactEvidence[];
}

export interface AgentApproval {
  id: string;
  proposalId: string;
  runId: string;
  proposalVersion: number;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  requestedAt: string | number;
  decision?: 'APPROVE' | 'REJECT';
  reason?: string;
  revision: number;
}

export interface AgentSqlProposal {
  id: string;
  runId: string;
  proposalVersion: number;
  sqlSnapshot: string;
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  operationClass: 'READ' | 'WRITE' | 'DDL' | 'ADMIN';
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  estimatedImpact?: string;
  status: string;
}

export interface AgentArtifactDashboardRef {
  id: string;
  taskId: string;
  artifactId: string;
  artifactVersion: number;
  chartIndex: number;
  dashboardId: number;
  chartId: number;
  contentMode: AgentArtifactContentMode;
  publishedAt: string | number;
}

export type AgentTaskContextType = 'PINNED' | 'COMMENT' | 'ATTACHMENT' | 'CHAT_SNAPSHOT';

export interface AgentTaskContext {
  id: string;
  taskId: string;
  type: AgentTaskContextType;
  title?: string;
  content: string;
  attachmentName?: string;
  attachmentMimeType?: string;
  attachmentSize?: number;
  createdBy?: number;
  createdAt: string | number;
}

export interface AgentTaskDetail {
  connectorAudit?: boolean;
  connectorContext?: {
    executionMode: 'EXTERNAL_RUNTIME_DELEGATION';
    externalRuntimeName: string;
    authorizationAgentId: string;
    authorizationAgentName: string;
  };
  task: AgentTask;
  runs: AgentRun[];
  eventsByRunId: Record<string, AgentRunEvent[]>;
  artifacts: AgentArtifactDetail[];
  sqlProposals: AgentSqlProposal[];
  approvals: AgentApproval[];
  toolAttempts: Array<Record<string, unknown>>;
  dashboardPublications: AgentArtifactDashboardRef[];
  contexts: AgentTaskContext[];
}

export interface CreateAgentTaskRequest {
  title: string;
  description?: string;
  acceptanceCriteria?: string;
  priority?: number;
  assigneeAgentId: string;
  originType: 'BOARD' | 'CHAT' | 'CONSOLE' | 'API' | 'CONNECTOR';
  originSessionId?: string;
  originMessageId?: string;
  dataScopeSnapshot: AgentDataScope[];
}

export interface CreateAgentChatTaskRequest {
  sessionId?: string;
  messageId: string;
  content: string;
  taskDescription: string;
  assigneeAgentId: string;
  dataScopeSnapshot: AgentDataScope[];
  attachments?: IChatAttachment[];
}

export interface AgentChatTaskCreateResponse {
  sessionId: string;
  message: IChatMessage;
  taskDetail: AgentTaskDetail;
}

export interface CreateAgentDefinitionRequest {
  avatar?: string;
  name: string;
  description?: string;
  runtimeType: 'EMBEDDED_SPRING_AI' | 'EXTERNAL_AGENT';
  runtimeProfileId?: string;
  modelConfigId?: string;
  systemPrompt?: string;
  capabilities: string[];
  dataScopes: AgentDataScope[];
  dataWikiIds: string[];
  dataWikiBindings: AgentDataWikiBinding[];
  outputContract?: string;
}

export interface UpdateAgentDefinitionRequest extends CreateAgentDefinitionRequest {
  agentId: string;
  expectedRevision: number;
  status?: AgentDefinition['status'];
}

const listAgents = createRequest<void, AgentDefinition[]>('/api/agent/definitions');
const listRuntimeOptions = createRequest<void, AgentRuntimeOption[]>('/api/agent/runtime-options');
const refreshRuntimeDiscovery = createRequest<void, boolean>('/api/agent/runtime-discovery/refresh', {
  method: 'post',
});
const createAgent = createRequest<CreateAgentDefinitionRequest, AgentDefinition>('/api/agent/definitions', {
  method: 'post',
});
const updateAgent = createRequest<UpdateAgentDefinitionRequest, AgentDefinition>('/api/agent/definitions/:agentId', {
  method: 'post',
});
const archiveAgent = createRequest<{ agentId: string; expectedRevision: number }, AgentDefinition>(
  '/api/agent/definitions/:agentId/archive',
  { method: 'post' },
);
const listTasks = createRequest<void, AgentTask[]>('/api/agent/tasks');
const listArchivedTasks = createRequest<void, AgentTask[]>('/api/agent/tasks/archived');
const getTask = createRequest<{ taskId: string }, AgentTaskDetail>('/api/agent/tasks/:taskId');
const createTask = createRequest<CreateAgentTaskRequest, AgentTaskDetail>('/api/agent/tasks', { method: 'post' });
const createTaskFromChat = createRequest<CreateAgentChatTaskRequest, AgentChatTaskCreateResponse>(
  '/api/agent/tasks/from-chat',
  { method: 'post' },
);
const listTaskSchedules = createRequest<void, AgentTaskSchedule[]>('/api/agent/task-schedules');
const getTaskSchedule = createRequest<{ scheduleId: string }, AgentTaskScheduleDetail>(
  '/api/agent/task-schedules/:scheduleId',
);
const createTaskSchedule = createRequest<SaveAgentTaskScheduleRequest, AgentTaskScheduleDetail>(
  '/api/agent/task-schedules', { method: 'post' },
);
const updateTaskSchedule = createRequest<SaveAgentTaskScheduleRequest, AgentTaskScheduleDetail>(
  '/api/agent/task-schedules/:scheduleId', { method: 'post' },
);
const previewTaskSchedule = createRequest<
  { expression: string; timezone: string },
  { nextRuns: Array<string | number> }
>('/api/agent/task-schedules/cron-preview');
const pauseTaskSchedule = createRequest<{ scheduleId: string; expectedRevision: number }, AgentTaskSchedule>(
  '/api/agent/task-schedules/:scheduleId/pause', { method: 'post' },
);
const resumeTaskSchedule = createRequest<{ scheduleId: string; expectedRevision: number }, AgentTaskSchedule>(
  '/api/agent/task-schedules/:scheduleId/resume', { method: 'post' },
);
const archiveTaskSchedule = createRequest<{ scheduleId: string; expectedRevision: number }, AgentTaskSchedule>(
  '/api/agent/task-schedules/:scheduleId/archive', { method: 'post' },
);
const runTaskScheduleNow = createRequest<{ scheduleId: string }, AgentTaskScheduleExecution>(
  '/api/agent/task-schedules/:scheduleId/run-now', { method: 'post' },
);
const transitionTask = createRequest<
  { taskId: string; expectedRevision: number; targetStatus: AgentTaskStatus },
  AgentTask
>('/api/agent/tasks/:taskId/transition', { method: 'post' });
const syncTaskScopes = createRequest<{ taskId: string; expectedRevision: number }, AgentTask>(
  '/api/agent/tasks/:taskId/scopes/sync',
  { method: 'post' },
);
const archiveTask = createRequest<{ taskId: string; expectedRevision: number }, AgentTask>(
  '/api/agent/tasks/:taskId/archive',
  { method: 'post' },
);
const deleteArchivedTask = createRequest<{ taskId: string; expectedRevision: number }, void>(
  '/api/agent/tasks/:taskId/delete',
  { method: 'post' },
);
const cancelRun = createRequest<{ runId: string }, AgentRun>('/api/agent/runs/:runId/cancel', { method: 'post' });
const decideApproval = createRequest<
  { approvalId: string; expectedRevision: number; decision: 'APPROVE' | 'REJECT'; reason?: string },
  AgentApproval
>('/api/agent/approvals/:approvalId/decision', { method: 'post' });
const publishArtifact = createRequest<
  {
    artifactId: string;
    artifactVersion: number;
    chartIndex: number;
    dashboardId: number;
    contentMode: AgentArtifactContentMode;
  },
  AgentArtifactDashboardRef
>('/api/agent/artifacts/:artifactId/publish/dashboard', { method: 'post' });
const appendTaskContext = createRequest<
  {
    taskId: string;
    type: AgentTaskContextType;
    title?: string;
    content: string;
    attachmentName?: string;
    attachmentMimeType?: string;
    attachmentSize?: number;
  },
  AgentTaskContext
>('/api/agent/tasks/:taskId/contexts', { method: 'post' });
const continueTask = createRequest<{ taskId: string; content: string; agentId?: string }, AgentTaskDetail>(
  '/api/agent/tasks/:taskId/messages',
  { method: 'post' },
);

export default {
  listRuntimeOptions,
  refreshRuntimeDiscovery,
  listAgents,
  createAgent,
  updateAgent,
  archiveAgent,
  listTasks,
  listArchivedTasks,
  getTask,
  createTask,
  createTaskFromChat,
  listTaskSchedules,
  getTaskSchedule,
  createTaskSchedule,
  updateTaskSchedule,
  previewTaskSchedule,
  pauseTaskSchedule,
  resumeTaskSchedule,
  archiveTaskSchedule,
  runTaskScheduleNow,
  transitionTask,
  syncTaskScopes,
  archiveTask,
  deleteArchivedTask,
  cancelRun,
  decideApproval,
  publishArtifact,
  appendTaskContext,
  continueTask,
};
