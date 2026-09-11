import type { IDataSourceExecutionContext, ITableEditExecuteRequest } from '@/service/dmlRequest';

export interface UpdateSqlOperation {
  rowId?: string | number;
  type?: string;
  oldDataList?: any[];
  [key: string]: any;
}

export interface UpdateSqlResultData {
  tableName?: string;
  headerList?: any[];
  dataList?: Array<Array<{ value?: any } | null | undefined>>;
  executeSqlParams?: Partial<IDataSourceExecutionContext>;
}

export function appendMissingOldDataList(operations: UpdateSqlOperation[], resultData: UpdateSqlResultData) {
  return operations.map((operation) => {
    if (operation.type !== 'UPDATE' || operation.oldDataList) {
      return operation;
    }
    const oldDataList = resultData.dataList?.find((data) => data[0]?.value === operation.rowId);
    return {
      ...operation,
      oldDataList: oldDataList?.map((cell) => cell?.value ?? null),
    };
  });
}

export function buildUpdateSqlRequestParams(operations: UpdateSqlOperation[], resultData: UpdateSqlResultData) {
  const { dataSourceId, databaseName, schemaName } = resultData.executeSqlParams || {};
  if (dataSourceId == null) {
    throw new Error('dataSourceId is required');
  }
  return {
    dataSourceId,
    databaseName,
    schemaName,
    tableName: resultData.tableName,
    headerList: resultData.headerList,
    operations: appendMissingOldDataList(operations, resultData),
  };
}

export async function resolveUpdateExecuteParams(params: {
  operations: UpdateSqlOperation[];
  resultData: UpdateSqlResultData;
  getUpdateDataSql: (requestParams: any) => Promise<string>;
}): Promise<ITableEditExecuteRequest> {
  const request = buildUpdateSqlRequestParams(params.operations, params.resultData);
  const sql = params.operations.length ? await params.getUpdateDataSql(request) : '';
  return {
    dataSourceId: request.dataSourceId,
    databaseName: request.databaseName,
    schemaName: request.schemaName,
    sql,
  };
}

export function getRequestErrorMessage(error: any) {
  if (typeof error === 'string') {
    return error;
  }
  return error?.errorMessage || error?.message || '';
}
