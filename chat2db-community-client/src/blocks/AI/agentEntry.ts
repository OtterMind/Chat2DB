import { ChatSourceType, QuestionType } from '@/constants/chat';
import type { DatabaseTypeCode } from '@/constants/common';
import type { IDBContextInfo } from '@/typings/database';
import type { AgentContextObject, AgentContextScope, AgentRunContextRequest } from '@/types/agentContext';
import type { IBoundInfo } from '@/typings';
import { captureAgentContext, contextScope } from './agentContext';

type DatabaseSelection = Pick<
  IBoundInfo,
  'dataSourceId' | 'dataSourceName' | 'databaseType' | 'databaseName' | 'schemaName'
>;

/**
 * One description of "the user asked the agent to do something from a surface that is not the chat
 * input". Every entry point (SQL editor context menu, console error, result row, `@` mention) fills
 * the same three slots, so the AI panel receives the same context shape wherever the request
 * started.
 */
export interface AgentEntry {
  /** What the entry asks for; it also drives the question card the panel renders. */
  intent: QuestionType;
  /** The text the user sees sent, built by the entry from its own payload. */
  input: string;
  /** Anchor: the connection scope this request belongs to. */
  scope?: AgentEntryScope | null;
  /** Anchor: the table or view the surface is opened on, added as a CURRENT_TABLE object. */
  currentTable?: IBoundInfo | null;
  /** Anchor: objects the user referenced, for example through `@` mentions. */
  mentions?: readonly AgentContextObject[];
  /** Payload: the selection the request is about, kept for callers that render it themselves. */
  payload?: AgentEntryPayload | null;
}

/**
 * Entries read their scope from callers whose types are looser than the editor binding: the
 * execution log carries the database type as a plain string. Both are accepted here and normalized
 * in one place.
 */
export interface AgentEntryScope {
  dataSourceId?: string | number | null;
  dataSourceName?: string | null;
  databaseType?: string | null;
  databaseName?: string | null;
  schemaName?: string | null;
  tableName?: string | null;
  viewName?: string | null;
}

export interface AgentEntryPayload {
  /** Selected SQL, or the single statement the entry acted on. */
  sql?: string;
  /** Failure text for the diagnose entries. */
  errorMessage?: string;
}

export type AgentEntryMode = 'send' | 'prefill';

export interface AgentEntryDetail {
  questionType: QuestionType;
  input: string;
  agentContext: AgentRunContextRequest;
  source: ChatSourceType;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  databaseType?: DatabaseTypeCode;
  sql?: string;
}

const asSelection = (scope?: AgentEntryScope | null): DatabaseSelection | null => {
  if (!scope || scope.dataSourceId === null || scope.dataSourceId === undefined) return null;
  const dataSourceId = typeof scope.dataSourceId === 'string' ? Number(scope.dataSourceId) : scope.dataSourceId;
  if (!Number.isFinite(dataSourceId)) return null;
  return {
    dataSourceId,
    dataSourceName: scope.dataSourceName ?? undefined,
    // The wire value is the same string union the enum carries; keep the cast in this one place.
    databaseType: (scope.databaseType ?? undefined) as DatabaseTypeCode | undefined,
    databaseName: scope.databaseName ?? undefined,
    schemaName: scope.schemaName ?? undefined,
  };
};

/** The anchor in the shape the AI store's context selector uses. */
export const agentEntryCascaderData = (scope?: AgentEntryScope | null): IDBContextInfo | null => {
  const normalized: AgentContextScope | null = contextScope(asSelection(scope));
  if (!normalized) return null;
  return {
    dataSourceId: Number(normalized.dataSourceId),
    dataSourceName: normalized.dataSourceName,
    databaseType: normalized.databaseType as DatabaseTypeCode | undefined,
    databaseName: normalized.database ?? undefined,
    schemaName: normalized.schema ?? undefined,
  };
};

/** The context the AI panel stores with the run; identical for every entry point. */
export const buildAgentEntryContext = (entry: AgentEntry): AgentRunContextRequest =>
  captureAgentContext(asSelection(entry.scope), entry.currentTable ?? undefined, entry.mentions ?? []);

/**
 * The payload the panel listens for. The scope fields stay in place because the Spring AI chat
 * path reads them directly, while Pi runs use `agentContext`.
 */
export const buildAgentEntryDetail = (
  entry: AgentEntry,
  source: ChatSourceType = ChatSourceType.DATASOURCE_CHAT,
): AgentEntryDetail => {
  const cascader = agentEntryCascaderData(entry.scope);
  return {
    questionType: entry.intent,
    input: entry.input,
    agentContext: buildAgentEntryContext(entry),
    source,
    ...(cascader
      ? {
          dataSourceId: cascader.dataSourceId,
          databaseName: cascader.databaseName,
          schemaName: cascader.schemaName,
          databaseType: cascader.databaseType,
        }
      : {}),
    ...(entry.payload?.sql ? { sql: entry.payload.sql } : {}),
  };
};
