import createRequest from './base';
// import { IPageResponse,IPageParams,IHistoryRecord, IWindowTab, ISavedConsole } from '@/types';
import { DatabaseTypeCode, ConsoleStatus } from '@/constants';
import { ICreateConsole, IConsole, IPageResponse, IPageParams } from '@/typings';

export interface IGetSavedListParams extends IPageParams {
  tabOpened?: 'y' | 'n';
  status?: ConsoleStatus;
  // When true, order by update time descending; otherwise use creation time ascending.
  orderByDesc?: boolean;
}

export enum OperationTypeEnum {
  /**
   *SQL execution
   */
  SQL_EXECUTE = 'SQL_EXECUTE',
  /**
   * SQL audit
   */
  SQL_AUDIT = 'SQL_AUDIT',
}
export interface IGetHistoryListParams extends IPageParams {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  searchKey?: string;
  operationType: OperationTypeEnum;
}
export interface ISaveBasicInfo {
  name: string;
  type: DatabaseTypeCode;
  ddl: string;
  dataSourceId: number;
  databaseName: string;
}

export interface IUpdateConsoleParams {
  id: number;
}

export interface IHistoryRecord {
  /**
   * Whether it can be connected
   */
  connectable?: boolean | null;
  /**
   * DB name
   */
  databaseName?: null | string;
  /**
   *Data source id
   */
  dataSourceId?: number | null;
  /**
   * Data source name
   */
  dataSourceName?: null | string;
  /**
   * ddl content
   */
  ddl?: null | string;
  /**
   * Whether ddl is truncated by the list interface
   */
  more?: boolean;
  /**
   *Extended information
   */
  extendInfo?: null | string;
  /**
   * Primary key
   */
  id?: number | null;
  /**
   * File alias
   */
  name?: null | string;
  /**
   * Number of operation lines
   */
  operationRows?: number | null;
  /**
   * schema name
   */
  schemaName?: null | string;
  /**
   * Status
   */
  status?: null | string;
  /**
   * ddl language type
   */
  type?: null | string;
  /**
   * Duration of use
   */
  useTime?: number | null;
  /**
   * Creation time
   */
  gmtCreate: string;

  /**
   *Execution user name
   */
  userName?: string;
}

const createConsole = createRequest<ICreateConsole, number>('/api/operation/saved/create', { method: 'post' });

const getSavedConsole = createRequest<{ id: number }, IConsole | null>('/api/operation/saved', {
  method: 'get',
});

const updateSavedConsole = createRequest<Partial<IConsole> & { id: number }, number>('/api/operation/saved/update', {
  method: 'post',
  errorLevel: false,
});

// orderByDesc true descending order
const getConsoleList = createRequest<IGetSavedListParams, IPageResponse<IConsole>>('/api/operation/saved/list', {
  errorLevel: false,
});

const deleteSavedConsole = createRequest<{ id: number }, string>('/api/operation/saved', { method: 'delete' });

const createHistory = createRequest<ISaveBasicInfo, void>('/api/operation/log/create', { method: 'post' });

const getHistoryList = createRequest<IGetHistoryListParams, IPageResponse<IHistoryRecord>>(
  '/api/operation/log/list',
  {},
);

const getHistoryDetail = createRequest<{ id: number }, IHistoryRecord>('/api/operation/log', {
  method: 'get',
});

export default {
  getConsoleList,
  updateSavedConsole,
  getHistoryList,
  getHistoryDetail,
  createConsole,
  deleteSavedConsole,
  createHistory,
  getSavedConsole,
};
