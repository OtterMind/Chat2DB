import type { IExecutionMetrics } from '@/typings';

type DurationTranslationKey = 'common.text.executeDuration' | 'common.text.fetchDuration';

export type DurationTranslator = (key: DurationTranslationKey, durationMs: number) => string;

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

export function formatResultSummary(resultData: ResultSummaryData, translate: DurationTranslator) {
  const { executionMetrics } = resultData;
  const rowCount = isNumber(executionMetrics?.fetchedRowCount)
    ? executionMetrics.fetchedRowCount
    : resultData.dataList?.length || 0;

  if (!isNumber(executionMetrics?.totalDurationMs)) {
    return `rows: ${rowCount}`;
  }

  const details: string[] = [];
  if (isNumber(executionMetrics.executeDurationMs)) {
    details.push(compactMilliseconds(translate('common.text.executeDuration', executionMetrics.executeDurationMs)));
  }
  if (isNumber(executionMetrics.fetchDurationMs)) {
    details.push(compactMilliseconds(translate('common.text.fetchDuration', executionMetrics.fetchDurationMs)));
  }

  const detailSummary = details.length ? ` (${details.join(' · ')})` : '';
  return `cost: ${executionMetrics.totalDurationMs}ms${detailSummary} rows: ${rowCount}`;
}
