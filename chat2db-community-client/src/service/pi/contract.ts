import type { AgentEvent, AgentRun, AgentRuntimeEnableResult,
  AgentRuntimeFeatureState, AgentSession, AgentToolFeatureState, AgentToolState, AgentWorkspaceSettings } from './types';
import type { AgentRunContextRequest } from '@/types/agentContext';
import type { AgentOutputPage } from '@/types/agentOutput';
import type { QuestionAnswer, QuestionResponse } from '@/types/question';
import type { IChatSession } from '../aiStream';
import type { IChatAttachment } from '../aiAttachment';
import type { IAIModelConfigItem, IAIModelConfigSaveRequest } from '../aiModelConfig';

export const PI_ENDPOINT = '/api/v3/ai/pi/invoke';
export const PI_PROTOCOL_VERSION = 1;
export type PiCallOptions = { signal?: AbortSignal; timeoutMs?: number };
type Operation<Input, Output> = { input: Input; output: Output };
type Session = { sessionId: string };
export type PiOutput = Session & { artifactId: string };
export interface PiOutputSearch {
  matches: { line: number; content: string; byteOffset: number }[];
  nextCursor?: string | null; hasMore: boolean; warning?: string | null;
}

export interface PiOperations {
  'skills.list': Operation<void, string[]>;
  'runtime.list': Operation<void, AgentRuntimeFeatureState[]>;
  'runtime.check': Operation<void, AgentRuntimeFeatureState>;
  'runtime.enable': Operation<{ confirmed: true }, AgentRuntimeEnableResult>;
  'runtime.disable': Operation<void, AgentRuntimeFeatureState>;
  'bash.check': Operation<void, AgentToolFeatureState>;
  'bash.enable': Operation<{ confirmed: true }, AgentToolFeatureState>;
  'bash.disable': Operation<void, AgentToolFeatureState>;
  'tools.list': Operation<void, AgentToolState[]>;
  'tools.setEnabled': Operation<{ toolName: string; enabled: boolean }, AgentToolState>;
  'workspace.get': Operation<void, AgentWorkspaceSettings>;
  'workspace.set': Operation<AgentWorkspaceSettings, AgentWorkspaceSettings>;
  'workspace.selectDirectory': Operation<void, string | null>;
  'sessions.list': Operation<void, IChatSession[]>;
  'sessions.create': Operation<{ message: string; runtimeType: 'PI'; modelConfigId: string }, AgentSession>;
  'sessions.get': Operation<Session & { sessionVersion: 2 }, IChatSession>;
  'sessions.rename': Operation<Session & { title: string }, AgentSession>;
  'sessions.delete': Operation<Session, void>;
  'runs.start': Operation<Session & {
    modelConfigId: string; message: string; idempotencyKey: string; context?: AgentRunContextRequest;
  }, AgentRun>;
  'runs.cancel': Operation<Session & { runId: string }, AgentRun>;
  'events.list': Operation<Session & {
    afterSequence?: number; beforeSequence?: number; limit?: number;
  }, AgentEvent[]>;
  'approvals.list': Operation<Session, { id: string }[]>;
  'approvals.decide': Operation<Session & {
    approvalId: string; decision: 'ALLOW_ONCE' | 'ALLOW_TOOL' | 'ALLOW_SERVER' | 'DENY';
  }, void>;
  'questions.list': Operation<Session, { id: string }[]>;
  'questions.answer': Operation<Session & { questionId: string } & QuestionResponse, QuestionAnswer>;
  'outputs.read': Operation<PiOutput & { cursor?: string; offset?: number; limit?: number }, AgentOutputPage>;
  'outputs.search': Operation<PiOutput & {
    pattern: string; cursor?: string; limit?: number; literal?: boolean; ignoreCase?: boolean;
  }, PiOutputSearch>;
  'outputs.save': Operation<PiOutput, string | null>;
  'attachments.parseLocal': Operation<{ filePath: string; fileName?: string }, IChatAttachment>;
  'models.prepare': Operation<IAIModelConfigSaveRequest, IAIModelConfigItem>;
}

export type PiOperation = keyof PiOperations;
export interface PiRequest {
  protocolVersion: typeof PI_PROTOCOL_VERSION;
  requestId: string;
  operation: PiOperation;
  payload: unknown;
}
export interface PiResponse {
  protocolVersion: number;
  requestId: string;
  success: boolean;
  data?: unknown;
  errorCode?: string;
  errorMessage?: string;
}
export interface PiTransport {
  invoke(request: PiRequest, signal: AbortSignal): Promise<PiResponse>;
}
export type PiSelectedFile = { fileName?: string; file?: File; filePath?: string };
export interface PiHostAdapter {
  selectFiles(types: string[]): Promise<PiSelectedFile[]>;
  parseAttachment(file: PiSelectedFile): Promise<IChatAttachment>;
  selectDirectory(current: string, options?: PiCallOptions): Promise<string | null>;
  downloadOutput(output: PiOutput, options?: PiCallOptions): Promise<void>;
}
