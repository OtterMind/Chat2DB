import { DEFAULT_RESULT_PAGE_SIZE } from '@/constants/pagination';

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
