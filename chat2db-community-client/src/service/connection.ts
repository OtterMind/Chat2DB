import { DatabaseTypeCode } from '@/constants';
import {
  IConnectionDetails,
  IConnectionEnv,
  IConnectionListItem,
  ICreateConnectionDetails,
  IDatabaseItem,
  IPageParams,
  IPageResponse,
} from '@/typings';
import { ISchemaItem } from '@/typings/schema';
import { UpdatePositionInTree } from '@/typings/tree';
import createRequest from './base';

export interface IDriverResponse {
  driverConfigList: {
    jdbcDriver: string;
    jdbcDriverClass: string;
    custom?: boolean;
  }[];
  defaultDriverConfig: {
    jdbcDriverClass: string;
  };
}

interface IDriverParams {
  dbType: DatabaseTypeCode;
}

interface IUploadDriver {
  file: any;
  jdbcDriverClass: string;
  dbType: string;
}

/**
 * Query the connection list
 */
const getList = createRequest<IPageParams, IPageResponse<IConnectionDetails>>('/api/connection/datasource/list', {
  errorLevel: false,
});

const getDetails = createRequest<{ id: number }, IConnectionDetails>('/api/connection/datasource', {});

const save = createRequest<ICreateConnectionDetails, IConnectionDetails>('/api/connection/datasource/create', {
  method: 'post',
  delayTime: true,
});

const test = createRequest<IConnectionDetails, boolean>('/api/connection/datasource/pre_connect', {
  method: 'post',
  delayTime: true,
});

const testSSH = createRequest<any, boolean>('/api/connection/ssh/pre_connect', {
  method: 'post',
  delayTime: true,
});

const update = createRequest<IConnectionDetails, IConnectionDetails>('/api/connection/datasource/update', {
  method: 'post',
});

const remove = createRequest<{ id: number }, void>('/api/connection/datasource', { method: 'delete' });

const getDatabaseList = createRequest<{ dataSourceId: number; refresh?: boolean }, IDatabaseItem[]>(
  '/api/rdb/database/list',
);

const getSchemaList = createRequest<{ dataSourceId: number; databaseName?: string; refresh?: boolean }, ISchemaItem>(
  '/api/rdb/schema/list',
);

const getDriverList = createRequest<IDriverParams, IDriverResponse>('/api/jdbc/driver/list', {
  errorLevel: false,
  method: 'get',
});

const downloadDriver = createRequest<{ dbType: string }, void>('/api/jdbc/driver/download', {
  method: 'get',
});

const saveDriver = createRequest<IUploadDriver, void>('/api/jdbc/driver/save', { method: 'post' });

const deleteDriver = createRequest<{ dbType: string; jdbcDriver: string[] }, void>('/api/jdbc/driver/delete', {
  method: 'delete',
});

const getEnvList = createRequest<void, IConnectionEnv[]>('/api/common/environment/list_all', { errorLevel: false });

const importConnection = createRequest<{ file: FormData }, void>('/api/converter/upload');

const importCommunitDataSource = createRequest<void, void>('/api/connection/datasource/import_community');

export interface NamespacesItem {
  id: number;
  name: string;
  position: number;
  dataSources: IConnectionListItem[];
}

export type NamespaceTreeListItem = {
  id: number;
  type: 'NAMESPACE' | 'DATA_SOURCE';
  data: NamespacesItem | IConnectionListItem;
  name: string;
  children: NamespaceTreeListItem[];
};

// Add, delete, modify and query groups of data sources
const createNamespace = createRequest<{ name: string; parentId?: number }, number>('/api/namespaces/create', {
  method: 'post',
});
const deleteNamespace = createRequest<{ id: number }, void>('/api/namespaces/delete', { method: 'post' });
const updateNamespace = createRequest<{ id: number; name: string }, void>('/api/namespaces/update', { method: 'post' });
const getNamespaceList = createRequest<{ refresh?: boolean }, NamespaceTreeListItem[]>('/api/namespaces/tree_list', {
  errorLevel: false,
});

// Update the location of a data source or group
const updatePosition = createRequest<UpdatePositionInTree, void>('/api/namespaces/update_position', {
  method: 'post',
});

// Import data source
const importNavicatConnections = createRequest<{ file: FormData }, void>('/api/converter/ncx/upload', {
  method: 'post',
  contentType: 'formData',
});

const importDBeaverConnections = createRequest<{ file: FormData }, void>('/api/converter/dbp/upload', {
  method: 'post',
  contentType: 'formData',
});

const importChat2DBConnections = createRequest<{ file: FormData }, void>('/api/converter/chat2db/upload', {
  method: 'post',
  contentType: 'formData',
});

const importDatagripConnections = createRequest<{ text: string }, void>('/api/converter/datagrip/upload', {
  method: 'post',
});

const exportDataSource = createRequest<
  { datasourceIds: number[] | null },
  {
    count: number;
    message: string;
  }
>('/api/connection/datasource/export', {
  method: 'post',
});

const closeConnection = createRequest<{ id: number }, void>('/api/connection/close', {
  method: 'get',
});

export default {
  getEnvList,
  getList,
  getDetails,
  save,
  test,
  update,
  remove,
  getDatabaseList,
  getSchemaList,
  testSSH,
  getDriverList,
  downloadDriver,
  saveDriver,
  deleteDriver,
  importConnection,
  importCommunitDataSource,
  createNamespace,
  updateNamespace,
  deleteNamespace,
  getNamespaceList,
  updatePosition,
  importNavicatConnections,
  importDBeaverConnections,
  importChat2DBConnections,
  importDatagripConnections,
  exportDataSource,
  closeConnection,
};
