import type { IExecutionMetrics } from '@/typings';

type ResultSummaryTranslationKey =
  | 'workspace.resultSet.cost'
  | 'workspace.resultSet.rows'
  | 'common.text.executeDuration'
  | 'common.text.fetchDuration';

export type ResultSummaryTranslator = (key: ResultSummaryTranslationKey, value: number) => string;

interface ResultSummaryData {
  dataList?: unknown[];
  executionMetrics?: IExecutionMetrics;
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function compactMilliseconds(value: string) {
  return value.replace(/\s+ms\b/gi, 'ms');
}

export function formatResultSummary(resultData: ResultSummaryData, translate: ResultSummaryTranslator) {
  const { executionMetrics } = resultData;
  const rowCount = isNumber(executionMetrics?.fetchedRowCount)
    ? executionMetrics.fetchedRowCount
    : resultData.dataList?.length || 0;
  const rowSummary = translate('workspace.resultSet.rows', rowCount);

  if (!isNumber(executionMetrics?.totalDurationMs)) {
    return rowSummary;
  }

  const details: string[] = [];
  if (isNumber(executionMetrics.executeDurationMs)) {
    details.push(compactMilliseconds(translate('common.text.executeDuration', executionMetrics.executeDurationMs)));
  }
  if (isNumber(executionMetrics.fetchDurationMs)) {
    details.push(compactMilliseconds(translate('common.text.fetchDuration', executionMetrics.fetchDurationMs)));
  }

  const detailSummary = details.length ? ` (${details.join(' · ')})` : '';
  const costSummary = compactMilliseconds(
    translate('workspace.resultSet.cost', executionMetrics.totalDurationMs),
  );
  return `${costSummary}${detailSummary} ${rowSummary}`;
}
