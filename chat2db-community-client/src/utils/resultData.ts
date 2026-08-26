import { v4 as uuidv4 } from 'uuid';
import type { IExecuteSqlParams, IManageResultData } from '@/typings';

export function processResultDataList(
  results: IManageResultData[],
  executeSqlParams: Omit<IExecuteSqlParams, 'sql'> & { sql?: string },
) {
  return results.map((item) => {
    const originalSql = item.originalSql || item.sql || executeSqlParams.sql || '';
    return {
      ...item,
      originalSql,
      uuid: uuidv4(),
      executeSqlParams: {
        ...executeSqlParams,
        single: undefined,
        resultSetId: item.resultSetId,
        sql: originalSql,
      },
    };
  });
}
