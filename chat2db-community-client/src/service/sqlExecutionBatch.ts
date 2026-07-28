import type { IManageResultData } from '@/typings';

export interface SqlResultHistoryMode {
  keepHistory: boolean;
  showResultCoordinates: boolean;
  resetResultSessionOnNextExecution: boolean;
}

export type SqlResultHistoryModeAction =
  | { type: 'setPreference'; keepHistory: boolean }
  | { type: 'beginExecution'; keepHistory: boolean };

export function createSqlResultHistoryMode(keepHistory: boolean): SqlResultHistoryMode {
  return {
    keepHistory,
    showResultCoordinates: keepHistory,
    resetResultSessionOnNextExecution: false,
  };
}

export function reduceSqlResultHistoryMode(
  state: SqlResultHistoryMode,
  action: SqlResultHistoryModeAction,
): SqlResultHistoryMode {
  if (action.type === 'setPreference') {
    return state.keepHistory === action.keepHistory
      ? state
      : {
          ...state,
          keepHistory: action.keepHistory,
          resetResultSessionOnNextExecution: true,
        };
  }
  return {
    ...state,
    showResultCoordinates: action.keepHistory,
    resetResultSessionOnNextExecution: false,
  };
}

export function getNextResultDisplayBatchSequence(currentSequence: number, resetHistorySession: boolean) {
  return resetHistorySession ? 1 : currentSequence + 1;
}

export function shouldKeepExistingExecutionResults(keepHistory: boolean, resetResultSession: boolean) {
  return keepHistory && !resetResultSession;
}

export function getSqlResultPreview(sql?: string) {
  const lines = (sql || '')
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const executableLines = lines.filter((line) => !line.startsWith('--'));
  return (executableLines.length ? executableLines : lines)
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function getResultExecutionSequence(result: IManageResultData) {
  return positiveInteger(result.extra?.executionSequence);
}

export function retainLatestResultBatches(resultDataList: IManageResultData[], limit: number) {
  if (limit <= 0) return [];
  const existingSequences = Array.from(
    new Set(
      resultDataList
        .map(getResultExecutionSequence)
        .filter((sequence): sequence is number => sequence !== undefined),
    ),
  );
  if (existingSequences.length <= limit) {
    return resultDataList;
  }
  const retainedSequences = new Set(existingSequences.sort((left, right) => right - left).slice(0, limit));
  return resultDataList.filter((result) => {
    const sequence = getResultExecutionSequence(result);
    return sequence === undefined || retainedSequences.has(sequence);
  });
}

export function shouldAcceptExecutionResult(
  executionSequence: number,
  latestReplacementExecutionSequence = 0,
) {
  return executionSequence >= latestReplacementExecutionSequence;
}

function positiveInteger(value: unknown) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : undefined;
}
