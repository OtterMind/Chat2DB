import { IDatabaseBaseInfo, IManageResultData } from '@/typings/database';
import { IChartItem } from '@/typings/dashboard';

export type DatabaseInfoAndMetaData = Pick<IChartItem, 'databaseInfo' | 'metaData'>;

export interface ChartSqlExecutionCallbackParams {
  databaseInfo: IDatabaseBaseInfo;
  data?: Array<Pick<IManageResultData, 'dataList' | 'headerList'>>;
}

export function buildChartExecutionResult({
  databaseInfo: { dataSourceId, dataSourceName, databaseType, databaseName, schemaName, sql },
  data = [],
}: ChartSqlExecutionCallbackParams): DatabaseInfoAndMetaData {
  const result = data[0];

  return {
    databaseInfo: { dataSourceId, dataSourceName, databaseType, databaseName, schemaName, sql },
    metaData: {
      dataList: result?.dataList ?? [],
      headerList: result?.headerList ?? [],
    } as IManageResultData,
  };
}
