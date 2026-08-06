import type { IManageResultData } from '@/typings';
import {
  createSqlResultHistoryMode,
  getNextResultDisplayBatchSequence,
  getSqlResultPreview,
  reduceSqlResultHistoryMode,
  retainLatestResultBatches,
  shouldAcceptExecutionResult,
  shouldKeepExistingExecutionResults,
} from './sqlExecutionBatch';
import { planSqlExecutionRetention } from './sqlExecutionRetention';

function assertEqual(actual: unknown, expected: unknown, message: string) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

function result(params: {
  executionSequence: number;
  executionId: string;
  statementSequence: number;
  resultSetId?: number;
  sql?: string;
}): IManageResultData {
  return {
    uuid: `${params.executionId}:${params.statementSequence}:${params.resultSetId || 1}`,
    dataList: [],
    headerList: [{ name: '#' }, { name: 'value' }] as any,
    description: '',
    sql: params.sql || 'select 1',
    originalSql: params.sql || 'select 1',
    success: true,
    sqlType: 'SELECT' as any,
    refreshTargets: [],
    resultSetId: params.resultSetId,
    statementSequence: params.statementSequence,
    extra: {
      executionSequence: params.executionSequence,
      executionId: params.executionId,
      statementSequence: params.statementSequence,
    },
    pageNo: 1,
    pageSize: 200,
    fuzzyTotal: '0',
    hasNextPage: false,
  };
}

const repeatedSqlResults = [
  result({ executionSequence: 1, executionId: 'execution-1', statementSequence: 1 }),
  result({ executionSequence: 2, executionId: 'execution-2', statementSequence: 1 }),
];

let historyMode = createSqlResultHistoryMode(true);
historyMode = reduceSqlResultHistoryMode(historyMode, { type: 'setPreference', keepHistory: false });
assertEqual(historyMode.keepHistory, false, 'disabling history updates the next-execution preference');
assertEqual(
  historyMode.showResultCoordinates,
  true,
  'disabling history does not relabel the currently retained results',
);
assertEqual(
  historyMode.resetResultSessionOnNextExecution,
  true,
  'changing the retention mode schedules a fresh display batch sequence',
);
historyMode = reduceSqlResultHistoryMode(historyMode, { type: 'beginExecution', keepHistory: false });
assertEqual(
  historyMode.showResultCoordinates,
  false,
  'the next replacement execution switches new result labels to SQL summaries',
);
assertEqual(
  historyMode.resetResultSessionOnNextExecution,
  false,
  'starting the replacement execution consumes the pending history-session reset',
);
historyMode = reduceSqlResultHistoryMode(historyMode, { type: 'setPreference', keepHistory: true });
assertEqual(
  historyMode.showResultCoordinates,
  false,
  're-enabling history also leaves the current replacement result label unchanged',
);
historyMode = reduceSqlResultHistoryMode(historyMode, { type: 'beginExecution', keepHistory: true });
assertEqual(
  historyMode.showResultCoordinates,
  true,
  'the next retained execution restores complete batch and statement coordinates',
);

let displayBatchSequence = 0;
displayBatchSequence = getNextResultDisplayBatchSequence(displayBatchSequence, false);
displayBatchSequence = getNextResultDisplayBatchSequence(displayBatchSequence, false);
displayBatchSequence = getNextResultDisplayBatchSequence(displayBatchSequence, false);
assertEqual(displayBatchSequence, 3, 'history batches increment within one retention session');
displayBatchSequence = getNextResultDisplayBatchSequence(displayBatchSequence, true);
assertEqual(displayBatchSequence, 1, 'switching retention mode restarts the next display batch at one');
displayBatchSequence = getNextResultDisplayBatchSequence(displayBatchSequence, false);
assertEqual(displayBatchSequence, 2, 'later executions continue within the new retention session');
displayBatchSequence = getNextResultDisplayBatchSequence(displayBatchSequence, true);
assertEqual(displayBatchSequence, 1, 'switching retention mode again starts another first batch');
assertEqual(
  shouldKeepExistingExecutionResults(true, false),
  true,
  'later history executions append results within the active retention session',
);
assertEqual(
  shouldKeepExistingExecutionResults(true, true),
  false,
  'the first history execution after a mode switch starts a clean result session',
);
assertEqual(
  shouldKeepExistingExecutionResults(false, false),
  false,
  'replace mode never keeps results from the previous execution',
);

const retentionMatrix = [
  {
    keepOutputHistory: true,
    keepResultHistory: true,
    expectedOutput: true,
    expectedResults: true,
  },
  {
    keepOutputHistory: true,
    keepResultHistory: false,
    expectedOutput: true,
    expectedResults: false,
  },
  {
    keepOutputHistory: false,
    keepResultHistory: true,
    expectedOutput: false,
    expectedResults: true,
  },
  {
    keepOutputHistory: false,
    keepResultHistory: false,
    expectedOutput: false,
    expectedResults: false,
  },
];
retentionMatrix.forEach((entry) => {
  const plan = planSqlExecutionRetention({
    keepOutputHistory: entry.keepOutputHistory,
    keepResultHistory: entry.keepResultHistory,
    resetResultSession: false,
  });
  const mode = `output=${entry.keepOutputHistory}, results=${entry.keepResultHistory}`;
  assertEqual(plan.keepExistingOutput, entry.expectedOutput, `${mode} keeps Output independently`);
  assertEqual(plan.keepExistingResults, entry.expectedResults, `${mode} keeps results independently`);
});

const resetResultSessionPlan = planSqlExecutionRetention({
  keepOutputHistory: true,
  keepResultHistory: true,
  resetResultSession: true,
});
assertEqual(
  resetResultSessionPlan.keepExistingOutput,
  true,
  'resetting the visible result session does not replace Output',
);
assertEqual(
  resetResultSessionPlan.keepExistingResults,
  false,
  'the first execution after a result-mode switch starts a clean result session',
);
assertEqual(
  getSqlResultPreview('-- list classes\nselect\n  *\nfrom class_test\nwhere grade_year = 2024;'),
  'select * from class_test where grade_year = 2024;',
  'formatted SQL is collapsed into an informative one-line result-tab summary',
);
assertEqual(
  getSqlResultPreview('-- comment only'),
  '-- comment only',
  'comment-only input still has a useful result-tab summary',
);

assertEqual(
  retainLatestResultBatches(repeatedSqlResults, 30)
    .map((item) => item.extra?.executionSequence)
    .join(','),
  '1,2',
  'the same SQL submitted twice remains as two flat retained results',
);

const multiResultStatement = [
  result({ executionSequence: 3, executionId: 'execution-3', statementSequence: 2, resultSetId: 1 }),
  result({ executionSequence: 3, executionId: 'execution-3', statementSequence: 2, resultSetId: 2 }),
];
assertEqual(
  multiResultStatement.every((item) => item.statementSequence === 2),
  true,
  'result artifacts preserve the statement coordinate',
);

const retainedResults = retainLatestResultBatches(
  [
    result({ executionSequence: 1, executionId: 'execution-1', statementSequence: 1 }),
    result({ executionSequence: 1, executionId: 'execution-1', statementSequence: 2 }),
    result({ executionSequence: 2, executionId: 'execution-2', statementSequence: 1 }),
    result({ executionSequence: 3, executionId: 'execution-3', statementSequence: 1 }),
  ],
  2,
);
assertEqual(
  retainedResults.map((item) => item.extra?.executionSequence).join(','),
  '2,3',
  'batch retention removes every result from the oldest batch together',
);

const retainedMultiResultBatches = retainLatestResultBatches(
  [
    result({ executionSequence: 1, executionId: 'execution-1', statementSequence: 1 }),
    result({ executionSequence: 2, executionId: 'execution-2', statementSequence: 1 }),
    result({ executionSequence: 3, executionId: 'execution-3', statementSequence: 1, resultSetId: 1 }),
    result({ executionSequence: 3, executionId: 'execution-3', statementSequence: 1, resultSetId: 2 }),
  ],
  2,
);
assertEqual(
  retainedMultiResultBatches.map((item) => item.extra?.executionSequence).join(','),
  '2,3,3',
  'multiple results from one execution consume one batch retention slot',
);

assertEqual(
  shouldAcceptExecutionResult(1),
  true,
  'append-only execution accepts results from every active batch',
);
assertEqual(
  shouldAcceptExecutionResult(1, 2),
  false,
  'a replacement batch rejects results from every earlier batch',
);
assertEqual(
  shouldAcceptExecutionResult(2, 2),
  true,
  'the replacement batch continues accepting its own results',
);
assertEqual(
  [2, 3].filter((sequence) => shouldAcceptExecutionResult(sequence, 2)).join(','),
  '2,3',
  'enabling history for a later batch keeps both the running replacement batch and the append batch active',
);
assertEqual(
  shouldAcceptExecutionResult(3, 2),
  true,
  'later append batches are accepted without invalidating the active replacement batch',
);

console.log('SQL execution batch tests passed');
