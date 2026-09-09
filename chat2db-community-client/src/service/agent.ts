import createRequest from './base';
import type { IChatSession } from './aiStream';

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
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_DECIDED'
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

const listRuntimeFeatures = createRequest<void, AgentRuntimeFeatureState[]>('/api/v3/ai/features');
const checkPi = createRequest<void, AgentRuntimeFeatureState>('/api/v3/ai/features/pi/check', { method: 'post' });
const enablePi = createRequest<{ confirmed: true }, AgentRuntimeEnableResult>('/api/v3/ai/features/pi/enable', {
  method: 'post',
});
const disablePi = createRequest<void, AgentRuntimeFeatureState>('/api/v3/ai/features/pi/disable', { method: 'post' });
const checkBash = createRequest<void, AgentToolFeatureState>('/api/v3/ai/features/bash/check', { method: 'post' });
const enableBash = createRequest<{ confirmed: true }, AgentToolFeatureState>('/api/v3/ai/features/bash/enable', {
  method: 'post',
});
const disableBash = createRequest<void, AgentToolFeatureState>('/api/v3/ai/features/bash/disable', { method: 'post' });
const createSession = createRequest<
  {
    message: string;
    runtimeType: AgentRuntimeType;
    modelConfigId: string;
  },
  AgentSession
>('/api/v3/ai/sessions', { method: 'post', errorLevel: false });
const getSession = createRequest<{ sessionId: string; sessionVersion: 2 }, IChatSession>(
  '/api/v3/ai/sessions/:sessionId',
  { errorLevel: false },
);
const startRun = createRequest<
  {
    sessionId: string;
    modelConfigId: string;
    message: string;
    idempotencyKey: string;
  },
  AgentRun
>('/api/v3/ai/sessions/:sessionId/runs', { method: 'post', errorLevel: false });
const cancelRun = createRequest<{ runId: string; sessionId: string }, AgentRun>('/api/v3/ai/runs/:runId/cancel', {
  method: 'post',
  errorLevel: false,
});
const listEvents = createRequest<{ sessionId: string; afterSequence: number; limit?: number }, AgentEvent[]>(
  '/api/v3/ai/sessions/:sessionId/events',
  { errorLevel: false },
);
const listApprovals = createRequest<{ sessionId: string }, { id: string }[]>(
  '/api/v3/ai/sessions/:sessionId/approvals', { errorLevel: false },
);
const decideApproval = createRequest<{ sessionId: string; approvalId: string; approved: boolean }, void>(
  '/api/v3/ai/sessions/:sessionId/approvals', { method: 'post', errorLevel: false },
);

export default {
  listRuntimeFeatures,
  checkPi,
  enablePi,
  disablePi,
  checkBash,
  enableBash,
  disableBash,
  createSession,
  getSession,
  startRun,
  cancelRun,
  listEvents,
  listApprovals,
  decideApproval,
};
