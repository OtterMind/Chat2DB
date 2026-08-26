import type { IExecuteSqlParams, IManageResultData } from '@/typings';

export function buildStreamResultExecuteSqlParams(
  executionParams: IExecuteSqlParams | undefined,
  result: Pick<IManageResultData, 'originalSql' | 'resultSetId'>,
  resultSequence: number,
) {
  if (!executionParams) {
    return undefined;
  }

  return {
    ...executionParams,
    sql: result.originalSql || executionParams.sql,
    resultSetId: result.resultSetId ?? resultSequence,
  };
}
