import type { IManageResultData, IResultConfig } from '@/typings';

export type PaginationNavigationType = 'pre' | 'next' | 'first' | 'last';

export interface IPaginationRequest {
  queryKey: string;
  pageNo: number;
  pageSize: number;
}

export interface IPaginationCountRequest {
  queryKey: string;
  resultGeneration: number;
  sequence: number;
}

export function getExactPaginationTotal(total: IResultConfig['total']): number | undefined {
  if (typeof total === 'number') {
    return Number.isFinite(total) ? total : undefined;
  }

  // The server returns an exact final-page total as a numeric string; only values ending in "+" are fuzzy.
  const normalizedTotal = total.trim();
  if (!normalizedTotal) {
    return undefined;
  }

  const parsedTotal = Number(normalizedTotal);
  return Number.isFinite(parsedTotal) ? parsedTotal : undefined;
}

/**
 * A page request changes its page number and size, but not the query identity.
 */
export function getPaginationQueryKey(
  resultData: Pick<IManageResultData, 'originalSql' | 'executeSqlParams'>,
): string {
  const { executeSqlParams } = resultData;
  return JSON.stringify({
    originalSql: resultData.originalSql,
    dataSourceId: executeSqlParams?.dataSourceId,
    databaseName: executeSqlParams?.databaseName,
    schemaName: executeSqlParams?.schemaName,
  });
}

export function isExpectedPaginationResponse(
  request: IPaginationRequest | undefined,
  queryKey: string,
  resultData: Pick<IManageResultData, 'pageNo' | 'pageSize'>,
): boolean {
  if (!request) {
    return false;
  }

  return (
    request.queryKey === queryKey &&
    request.pageNo === resultData.pageNo &&
    request.pageSize === resultData.pageSize
  );
}

export function isCurrentPaginationCountRequest(
  request: IPaginationCountRequest,
  currentRequest: IPaginationCountRequest,
): boolean {
  return (
    request.queryKey === currentRequest.queryKey &&
    request.resultGeneration === currentRequest.resultGeneration &&
    request.sequence === currentRequest.sequence
  );
}

export function updatePaginationPage(config: IResultConfig, pageNo: number): IResultConfig {
  return { ...config, pageNo };
}

export function updatePaginationPageSize(config: IResultConfig, pageSize: number): IResultConfig {
  return { ...config, pageNo: 1, pageSize };
}

export function isPaginationNavigationDisabled(
  paginationConfig: IResultConfig | undefined,
  type: PaginationNavigationType,
) {
  if (!paginationConfig) {
    return false;
  }
  if (type === 'first' || type === 'pre') {
    return paginationConfig.pageNo === 1;
  }
  if (type === 'next' || type === 'last') {
    const exactTotal = getExactPaginationTotal(paginationConfig.total);
    if (exactTotal !== undefined) {
      return paginationConfig.pageNo * paginationConfig.pageSize >= exactTotal;
    }
    return !paginationConfig.hasNextPage;
  }
  return true;
}

/**
 * Keep a previously requested exact total while a page refresh only supplies a fuzzy total.
 */
export function resolvePaginationTotal(
  currentTotal: IResultConfig['total'],
  latestFuzzyTotal: string,
  preserveExactTotal = true,
): IResultConfig['total'] {
  const exactTotal = getExactPaginationTotal(currentTotal);
  return preserveExactTotal && exactTotal !== undefined ? exactTotal : latestFuzzyTotal;
}
