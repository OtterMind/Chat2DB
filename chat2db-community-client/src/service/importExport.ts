import createRequest from './base';
import { IDatabaseBaseInfo } from '@/typings/database';
import { IPageResponse } from '@/typings';
import { ImportExportTaskDetails } from '@/typings/importExport';
import { taskStopRequest } from './taskStopRequest';

export interface ImportSqlFileParams extends IDatabaseBaseInfo {
  fileName: string;
}

export interface ImportOtherFileParams extends IDatabaseBaseInfo {
  fileName: string;
  tableName: string;
  containsHeader: boolean;
}

export interface ExportSqlFileParams extends IDatabaseBaseInfo {
  exportPath: string;
  tableName?: string;
  scope?: 'ALL' | 'SCHEMA' | 'TABLE';
  containsHeader?: boolean;
}

export interface TaskListParams {
  pageNo: number;
  pageSize: number;
}

const importSqlFile = createRequest<ImportSqlFileParams, number>('/api/import/sql_file', { method: 'post' });
const importOtherFile = createRequest<ImportOtherFileParams, number>('/api/import/other_file', { method: 'post' });

const exportSqlFile = createRequest<ExportSqlFileParams, number>('/api/export/sql_file', { method: 'post' });
const exportOtherFile = createRequest<IDatabaseBaseInfo, number>('/api/export/other_file', { method: 'post' });

const getTaskList = createRequest<TaskListParams, IPageResponse<ImportExportTaskDetails>>('/api/task/list', {
  method: 'get',
  errorLevel: false,
});
const getTaskDetails = createRequest<{ id: number }, ImportExportTaskDetails>('/api/task/get', { method: 'get' });

const stopTask = createRequest<{ id: number }, void>(taskStopRequest.path, { method: taskStopRequest.method });

// Generate Java classes
const generateJavaClass = createRequest<ExportSqlFileParams, number>('/api/rdb/table/generate/class', {
  method: 'post',
});

export default {
  importSqlFile,
  importOtherFile,
  exportSqlFile,
  exportOtherFile,
  getTaskList,
  getTaskDetails,
  stopTask,
  generateJavaClass,
};
