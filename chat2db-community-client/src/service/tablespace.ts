import createRequest from './base';
import type { IDatabaseObjectDeletePrepareVO } from './sql';

export interface ITablespaceBaseParams {
  dataSourceId: number;
}

export interface ITablespaceListParams extends ITablespaceBaseParams {
  refresh?: boolean;
}

export interface ITablespaceDetailParams extends ITablespaceBaseParams {
  tablespaceName: string;
}

export interface ITablespaceCreateSqlParams extends ITablespaceBaseParams {
  name: string;
  /**
   * Data-file path on the MySQL server filesystem. User-supplied and emitted verbatim; the
   * application never validates, canonicalizes, or writes this path.
   */
  dataFile: string;
  fileBlockSize?: number;
}

export interface ITablespaceModifyParams extends ITablespaceBaseParams {
  oldName: string;
  newName: string;
}

export interface ITablespaceDeletePrepareParams extends ITablespaceBaseParams {
  tablespaceName: string;
}

export interface ITablespaceDeleteExecuteParams extends ITablespaceBaseParams {
  tablespaceName: string;
  confirmName: string;
}

export interface Tablespace {
  name: string;
  engine?: string;
  spaceId?: number;
  dataFiles?: string[];
  fileBlockSize?: number;
  autoextendSize?: number;
  maxSize?: number;
  extentSize?: number;
  initialSize?: number;
  status?: string;
  comment?: string;
  occupyingTables?: string[];
}

export interface TablespaceCapability {
  renameSupported: boolean;
}

const list = createRequest<ITablespaceListParams, Tablespace[]>('/api/rdb/tablespace/list', {
  method: 'get',
  errorLevel: 'toast',
});

const detail = createRequest<ITablespaceDetailParams, Tablespace>('/api/rdb/tablespace/detail', {
  method: 'get',
  errorLevel: 'toast',
});

const createSql = createRequest<ITablespaceCreateSqlParams, { sql: string }>('/api/rdb/tablespace/create_sql', {
  method: 'post',
  errorLevel: 'toast',
});

const modify = createRequest<ITablespaceModifyParams, { success: boolean; message: string }>(
  '/api/rdb/tablespace/modify',
  { method: 'post', errorLevel: 'toast' },
);

const capability = createRequest<ITablespaceBaseParams, TablespaceCapability>(
  '/api/rdb/tablespace/capability',
  { method: 'get', errorLevel: false },
);

const prepareDelete = createRequest<ITablespaceDeletePrepareParams, IDatabaseObjectDeletePrepareVO>(
  '/api/rdb/delete/tablespace/prepare',
  { method: 'post', errorLevel: 'toast' },
);

const executeDelete = createRequest<ITablespaceDeleteExecuteParams, void>('/api/rdb/delete/tablespace/execute', {
  method: 'post',
  errorLevel: 'toast',
});

export default {
  list,
  detail,
  createSql,
  modify,
  capability,
  prepareDelete,
  executeDelete,
};
