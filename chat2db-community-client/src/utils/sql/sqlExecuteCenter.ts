import { IDatabaseBaseInfo, IManageResultData } from '@/typings/database';
import executeSqlServer from '@/service/executeSql';
import { IExecuteSqlParams } from '@/typings';
import { useGlobalStore } from '@/store/global';
import type { ISqlEditorExecuteRequest, ITableBrowseRequest } from '@/service/dmlRequest';

export interface IViewTableProps {
  // Open table name
  tableName?: string;
  // Pagination information
  pageSize?: number;
  pageNo?: number;
  // Database information for executing sql
  databaseBaseInfo: IDatabaseBaseInfo;
  // Other information
  // Approval ID
  applyId?: number;
}

interface IUnifiedSqlExecutorParams extends IExecuteSqlParams {
  databaseBaseInfo: IDatabaseBaseInfo;
}

// Default paging parameters
// export const defaultPaging = {
//   pageNo: 1,
//   pageSize: 200,
// }

// Unified sql execution entrance unifiedSqlExecutor
export function unifiedSqlExecutor(props: IUnifiedSqlExecutorParams, signal: any): Promise<IManageResultData[]> {
  const { sql, single, applyId, pageSize, pageNo, databaseBaseInfo } = props;
  const errorContinue = useGlobalStore.getState().editorSettings.errorContinue;
  if (databaseBaseInfo.dataSourceId == null) {
    return Promise.reject(new Error('dataSourceId is required'));
  }
  const request: ISqlEditorExecuteRequest = {
    dataSourceId: databaseBaseInfo.dataSourceId,
    databaseName: databaseBaseInfo.databaseName,
    schemaName: databaseBaseInfo.schemaName,
    sql,
    single,
    applyId,
    pageSize,
    pageNo,
    errorContinue,
  };
  return new Promise((resolve, reject) => {
    // //Interceptor
    // const interceptorRes = interceptor?.(sql);
    // if (interceptorRes) {
    //   return resolve(interceptorRes);
    // }
    // execute sql
    return executeSqlServer
      .executeSql(request, { signal })
      .then((res) => {
        resolve(res);
      })
      .catch(reject);
  });
}

// View table unified entrance
export const unifiedViewTable = (props: IViewTableProps) => {
  const { tableName, pageSize, pageNo, databaseBaseInfo } = props;
  if (databaseBaseInfo.dataSourceId == null || !tableName) {
    return Promise.reject(new Error('dataSourceId and tableName are required'));
  }
  const request: ITableBrowseRequest = {
    dataSourceId: databaseBaseInfo.dataSourceId,
    databaseName: databaseBaseInfo.databaseName,
    schemaName: databaseBaseInfo.schemaName,
    tableName,
    pageSize,
    pageNo,
  };
  return new Promise((resolve, reject) => {
    // execute sql
    return executeSqlServer
      .viewTable(request)
      .then((res) => {
        resolve(res);
      })
      .catch(reject);
  });
};
