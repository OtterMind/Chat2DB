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

export function getResultPagingErrorMessage(error: unknown) {
  if (typeof error === 'string') {
    return error;
  }
  if (error && typeof error === 'object') {
    const errorMessage = 'errorMessage' in error ? error.errorMessage : undefined;
    if (typeof errorMessage === 'string') {
      return errorMessage;
    }
    const message = 'message' in error ? error.message : undefined;
    if (typeof message === 'string') {
      return message;
    }
  }
  return '';
}

export async function runResultPagingRequest(
  request: () => Promise<unknown> | unknown,
  callbacks: {
    onSuccess?: () => void;
    onError: (message: string) => void;
  },
) {
  try {
    await request();
    callbacks.onSuccess?.();
  } catch (error) {
    callbacks.onError(getResultPagingErrorMessage(error));
  }
}
