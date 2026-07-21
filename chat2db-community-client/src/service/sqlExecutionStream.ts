import { JcefEventBus, JavaPushActionType } from '@/jcef/eventBus';
import createJcefApi from '@/jcef/base';
import { IExecuteSqlParams } from '@/service/executeSql';
import { IManageResultData } from '@/typings';
import { SqlTypeEnum } from '@/typings/sqlParser';

export type SqlExecutionEventType =
  | 'started'
  | 'statementStarted'
  | 'resultStarted'
  | 'rows'
  | 'updateCount'
  | 'message'
  | 'resultFinished'
  | 'statementFinished'
  | 'finished'
  | 'failed'
  | 'cancelled';

export interface SqlExecutionEvent<T = any> {
  executionId: string;
  eventSequence?: number;
  occurredAtEpochMs?: number;
  eventType: SqlExecutionEventType;
  statementSequence?: number;
  resultSequence?: number;
  resultKey?: string;
  message: T;
}

export interface SqlExecutionStartResult {
  executionId: string;
}

export interface SqlExecutionMessage {
  level?: 'INFO' | 'WARN' | 'ERROR';
  message: string;
  errorCode?: number;
  sqlState?: string | null;
  source?: string;
}

export interface SqlExecutionStatement {
  sql?: string;
  originalSql?: string;
  comment?: string;
  sequence?: number;
  historySequence?: number;
}

export function startSqlExecution(params: IExecuteSqlParams, requestUuid: string) {
  return createJcefApi<SqlExecutionStartResult>('sql-execute', params, requestUuid);
}

export function cancelSqlExecution(executionId: string) {
  return createJcefApi<boolean>('sql-cancel', { executionId });
}

export function onSqlExecutionEvent(requestUuid: string, callback: (event: SqlExecutionEvent) => void) {
  const eventType = `${JavaPushActionType.SQL_EXECUTION_EVENT}_${requestUuid}`;
  JcefEventBus.on(eventType, callback);
  return () => {
    JcefEventBus.off(eventType);
  };
}

export function mergeResultStarted(current: IManageResultData[], result: IManageResultData) {
  return mergeResultStartedWithMode(current, result, 'prepend');
}

export function appendResultStarted(current: IManageResultData[], result: IManageResultData) {
  return mergeResultStartedWithMode(current, result, 'append');
}

export function upsertResultStarted(current: IManageResultData[], result: IManageResultData) {
  return upsertResult(current, result, 'started');
}

function mergeResultStartedWithMode(
  current: IManageResultData[],
  result: IManageResultData,
  mode: 'prepend' | 'append',
) {
  const normalizedResult = ensureResultData(result);
  let matched = false;
  const next = current.map((item) => {
    if (!isSameResult(item, normalizedResult)) {
      return item;
    }
    matched = true;
    return {
      ...normalizedResult,
      uuid: item.uuid,
      displayName: item.displayName,
      extra: {
        ...(normalizedResult.extra || {}),
        messages: mergeExecutionMessages(item.extra?.messages, normalizedResult.extra?.messages),
      },
    };
  });
  if (matched) {
    return next;
  }
  return mode === 'append' ? [...current, normalizedResult] : [normalizedResult, ...current];
}

export function mergeRows(current: IManageResultData[], chunk: IManageResultData) {
  return current.map((item) => {
    if (!isSameResult(item, chunk)) {
      return item;
    }
    return {
      ...item,
      dataList: [...(item.dataList || []), ...(chunk.dataList || [])],
    };
  });
}

export function mergeResultFinished(current: IManageResultData[], result: IManageResultData) {
  return mergeResultFinishedWithMode(current, result, 'prepend');
}

export function appendResultFinished(current: IManageResultData[], result: IManageResultData) {
  return mergeResultFinishedWithMode(current, result, 'append');
}

export function upsertResultFinished(current: IManageResultData[], result: IManageResultData) {
  return upsertResult(current, result, 'finished');
}

function mergeResultFinishedWithMode(
  current: IManageResultData[],
  result: IManageResultData,
  mode: 'prepend' | 'append',
) {
  let matched = false;
  const next = current.map((item) => {
    if (!isSameResult(item, result)) {
      return item;
    }
    matched = true;
    return {
      ...ensureResultData(result),
      uuid: item.uuid,
      displayName: item.displayName,
      dataList: item.dataList?.length ? item.dataList : result.dataList,
      extra: {
        ...(result.extra || {}),
        messages: mergeExecutionMessages(item.extra?.messages, result.extra?.messages),
      },
    };
  });
  if (matched) {
    return next;
  }
  const normalizedResult = ensureResultData(result);
  return mode === 'append' ? [...current, normalizedResult] : [normalizedResult, ...current];
}

export function sortExecutionResults(current: IManageResultData[]) {
  return [...current].sort((left, right) => {
    const leftExecution = Number(left.extra?.executionSequence || 0);
    const rightExecution = Number(right.extra?.executionSequence || 0);
    if (leftExecution !== rightExecution) {
      return leftExecution - rightExecution;
    }
    const leftStatement = Number(left.extra?.statementSequence ?? left.statementSequence ?? 0);
    const rightStatement = Number(right.extra?.statementSequence ?? right.statementSequence ?? 0);
    if (leftStatement !== rightStatement) {
      return leftStatement - rightStatement;
    }
    const leftResult = Number(left.extra?.resultSequence || left.extra?.streamResultId || left.resultSetId || 0);
    const rightResult = Number(right.extra?.resultSequence || right.extra?.streamResultId || right.resultSetId || 0);
    if (leftResult !== rightResult) {
      return leftResult - rightResult;
    }
    return String(getResultSortIdentity(left)).localeCompare(String(getResultSortIdentity(right)));
  });
}

function getResultSortIdentity(item: IManageResultData) {
  return item.extra?.resultKey || item.extra?.historyKey || item.uuid || item.originalSql || item.sql || '';
}

function upsertResult(current: IManageResultData[], result: IManageResultData, stage: 'started' | 'finished') {
  const normalizedResult = ensureResultData(result);
  let matched = false;
  const next = current.map((item) => {
    if (matched) {
      return item;
    }
    const sameResult = isSameResult(item, normalizedResult);
    if (!sameResult) {
      return item;
    }
    matched = true;
    const mergeCurrentResult = isSameExecutionResult(item, normalizedResult);
    const messages = mergeCurrentResult
      ? mergeExecutionMessages(item.extra?.messages, normalizedResult.extra?.messages)
      : normalizedResult.extra?.messages;
    if (stage === 'finished') {
      return {
        ...normalizedResult,
        uuid: item.uuid,
        displayName: normalizedResult.displayName || item.displayName,
        dataList: mergeCurrentResult && item.dataList?.length ? item.dataList : normalizedResult.dataList,
        extra: {
          ...(normalizedResult.extra || {}),
          messages,
        },
      };
    }
    return {
      ...normalizedResult,
      uuid: item.uuid,
      displayName: normalizedResult.displayName || item.displayName,
      extra: {
        ...(normalizedResult.extra || {}),
        messages,
      },
    };
  });
  if (matched) {
    return next;
  }
  return [...current, normalizedResult];
}

function isSameExecutionResult(left: IManageResultData, right: IManageResultData) {
  const leftResultKey = left.extra?.resultKey;
  const rightResultKey = right.extra?.resultKey;
  if (leftResultKey !== undefined && rightResultKey !== undefined) {
    if (leftResultKey === rightResultKey) {
      return true;
    }
    const leftExecutionId = left.extra?.executionId;
    const rightExecutionId = right.extra?.executionId;
    if (leftExecutionId !== undefined && rightExecutionId !== undefined && leftExecutionId === rightExecutionId) {
      return false;
    }
  }

  const leftExecutionId = left.extra?.executionId;
  const rightExecutionId = right.extra?.executionId;
  const leftStatementSequence = left.extra?.statementSequence;
  const rightStatementSequence = right.extra?.statementSequence;
  const leftResultSequence = left.extra?.resultSequence;
  const rightResultSequence = right.extra?.resultSequence;

  if (
    leftExecutionId !== undefined &&
    rightExecutionId !== undefined &&
    leftExecutionId === rightExecutionId &&
    leftStatementSequence !== undefined &&
    rightStatementSequence !== undefined &&
    leftStatementSequence === rightStatementSequence &&
    leftResultSequence !== undefined &&
    rightResultSequence !== undefined
  ) {
    return leftResultSequence === rightResultSequence;
  }

  return (
    leftExecutionId !== undefined &&
    rightExecutionId !== undefined &&
    leftExecutionId === rightExecutionId &&
    leftStatementSequence !== undefined &&
    rightStatementSequence !== undefined &&
    leftStatementSequence === rightStatementSequence
  );
}

export function mergeExecutionMessages(
  ...messageGroups: Array<SqlExecutionMessage[] | undefined>
): SqlExecutionMessage[] | undefined {
  const messages = messageGroups.flatMap((group) => group || []);
  if (!messages.length) {
    return undefined;
  }
  return messages.filter(Boolean);
}

export function attachExecutionIdentity(
  result: IManageResultData,
  executionId: string,
  statementSequence?: number,
): IManageResultData {
  return {
    ...result,
    extra: {
      ...(result.extra || {}),
      executionId,
      statementSequence,
    },
  };
}

function ensureResultData(result: IManageResultData) {
  const normalized = { ...result };
  return {
    ...normalized,
    description: normalized.description || '',
    sql: normalized.sql || normalized.originalSql || '',
    originalSql: normalized.originalSql || normalized.sql || '',
    success: normalized.success ?? true,
    duration: normalized.duration || 0,
    headerList: normalized.headerList || [],
    dataList: normalized.dataList || [],
    sqlType: normalized.sqlType || SqlTypeEnum.OTHER,
    refreshTargets: normalized.refreshTargets || [],
    pageNo: normalized.pageNo || 1,
    pageSize: normalized.pageSize || 0,
    fuzzyTotal: normalized.fuzzyTotal || '0',
    hasNextPage: normalized.hasNextPage ?? false,
  };
}

function isSameResult(left: IManageResultData, right: IManageResultData) {
  const leftResultKey = left.extra?.resultKey;
  const rightResultKey = right.extra?.resultKey;
  const leftExecutionId = left.extra?.executionId;
  const rightExecutionId = right.extra?.executionId;
  if (leftResultKey !== undefined && rightResultKey !== undefined) {
    if (leftResultKey === rightResultKey) {
      return true;
    }
    if (leftExecutionId !== undefined && rightExecutionId !== undefined && leftExecutionId === rightExecutionId) {
      return false;
    }
  }

  const leftHistoryKey = left.extra?.historyKey;
  const rightHistoryKey = right.extra?.historyKey;
  if (leftHistoryKey !== undefined && rightHistoryKey !== undefined) {
    if (leftHistoryKey === rightHistoryKey) {
      return true;
    }
    if (leftExecutionId !== undefined && rightExecutionId !== undefined && leftExecutionId !== rightExecutionId) {
      return false;
    }
  }

  const leftStatementSequence = left.extra?.statementSequence;
  const rightStatementSequence = right.extra?.statementSequence;
  const leftResultSequence = left.extra?.resultSequence;
  const rightResultSequence = right.extra?.resultSequence;
  if (
    leftExecutionId !== undefined &&
    rightExecutionId !== undefined &&
    leftExecutionId === rightExecutionId &&
    leftStatementSequence !== undefined &&
    rightStatementSequence !== undefined &&
    leftStatementSequence === rightStatementSequence &&
    leftResultSequence !== undefined &&
    rightResultSequence !== undefined
  ) {
    return leftResultSequence === rightResultSequence;
  }

  if (leftExecutionId !== undefined && rightExecutionId !== undefined && leftExecutionId !== rightExecutionId) {
    return false;
  }

  const leftStreamResultId = left.extra?.streamResultId;
  const rightStreamResultId = right.extra?.streamResultId;
  if (leftStreamResultId !== undefined && rightStreamResultId !== undefined) {
    return leftStreamResultId === rightStreamResultId;
  }

  if (
    leftExecutionId !== undefined &&
    rightExecutionId !== undefined &&
    leftStatementSequence !== undefined &&
    rightStatementSequence !== undefined
  ) {
    return leftExecutionId === rightExecutionId && leftStatementSequence === rightStatementSequence;
  }

  if (left.resultSetId !== undefined && right.resultSetId !== undefined) {
    return left.resultSetId === right.resultSetId && left.originalSql === right.originalSql;
  }
  return left.originalSql === right.originalSql && left.sql === right.sql;
}
