export type AgentRuntimeType = 'PI' | 'CODEX' | 'DSH';
export type AgentEventType =
  | 'RUN_ACCEPTED'
  | 'RUN_STARTED'
  | 'ASSISTANT_MESSAGE_STARTED'
  | 'ASSISTANT_TEXT_DELTA'
  | 'ASSISTANT_REASONING_DELTA'
  | 'TOOL_CALL_REQUESTED'
  | 'TOOL_CALL_RUNNING'
  | 'TOOL_CALL_COMPLETED'
  | 'TOOL_CALL_FAILED'
  | 'CHART_CREATED'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_DECIDED'
  | 'QUESTION_REQUESTED'
  | 'QUESTION_ANSWERED'
  | 'QUESTION_CLOSED'
  | 'USAGE_UPDATED'
  | 'CHECKPOINT_COMMITTED'
  | 'RUN_COMPLETED'
  | 'RUN_FAILED'
  | 'RUN_CANCELLED'
  | 'RUN_SUSPENDED'
  | 'RUN_OUTCOME_UNKNOWN';

export interface AgentEnvironmentReport {
  runtimeType: AgentRuntimeType;
  status: 'READY' | 'DEGRADED' | 'BLOCKED';
  runtimeVersion?: string;
  operatingSystem: string;
  architecture: string;
  checks: string[];
  diagnostics: Record<string, string>;
  checkedAt: string;
}

export interface AgentRuntimeFeatureState {
  runtimeType: AgentRuntimeType;
  enabled: boolean;
  installed: boolean;
  environment: AgentEnvironmentReport;
}

export interface AgentRuntimeEnableResult {
  state: AgentRuntimeFeatureState;
  taskId?: number;
}

export interface AgentToolFeatureState {
  feature: 'BASH';
  enabled: boolean;
  available: boolean;
  checks: string[];
  diagnostics: Record<string, string>;
}

export interface AgentToolState {
  name: string;
  description: string;
  category: 'DATABASE' | 'BUILTIN' | 'INTERACTION' | 'VISUALIZATION';
  status: 'ENABLED' | 'DISABLED' | 'UNAVAILABLE';
}

export interface AgentWorkspaceSettings {
  workingDirectory: string;
}

export interface AgentSession {
  id: string;
  title: string;
  schemaVersion: 2;
  runtimeBinding: { runtimeType: AgentRuntimeType };
}

export interface AgentRun {
  id: string;
  sessionId: string;
  status: string;
  externalRunId?: string;
  failure?: { code: string; message: string };
}

export interface AgentEvent {
  id: string;
  sessionId: string;
  runId?: string;
  sequence: number;
  type: AgentEventType;
  payload: Record<string, unknown>;
  occurredAt: string;
}
