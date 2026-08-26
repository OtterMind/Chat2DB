import { DEFAULT_RESULT_PAGE_SIZE } from '@/constants/pagination';
import type { IExecuteSqlParams } from '@/typings';

export interface ResultPaging {
  pageNo: number;
  pageSize: number;
}

export function resolveResultPaging(
  current: Partial<ResultPaging> | undefined,
  override?: Partial<ResultPaging>,
): ResultPaging {
  return {
    pageNo: override?.pageNo ?? current?.pageNo ?? 1,
    pageSize: override?.pageSize ?? current?.pageSize ?? DEFAULT_RESULT_PAGE_SIZE,
  };
}

export function buildResultPageExecuteParams(
  current: IExecuteSqlParams,
  paging: ResultPaging,
  sql?: string,
): IExecuteSqlParams {
  return {
    ...current,
    ...paging,
    ...(sql !== undefined ? { sql } : {}),
  };
}
