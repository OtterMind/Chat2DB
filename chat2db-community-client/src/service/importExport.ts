import createRequest from './base';
import { IDatabaseBaseInfo } from '@/typings/database';
import { IPageResponse } from '@/typings';
import { ImportExportTaskDetails, ImportExportTaskEvent } from '@/typings/importExport';
import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import { isDesktop } from '@/utils/env';
import { resolveImportTaskTransport } from './importTaskTransport';

export interface GenerateJavaClassParams extends IDatabaseBaseInfo {
  exportPath: string;
  tableName?: string;
  scope?: 'ALL' | 'SCHEMA' | 'TABLE';
  containsHeader?: boolean;
}

export interface TaskListParams {
  pageNo: number;
  pageSize: number;
  status?: string;
}

export interface TaskSubmissionResponse {
  taskId: number;
}

export interface TaskEventListParams {
  taskId: number;
  afterSequence?: number;
  beforeSequence?: number;
  limit: number;
}

export interface TaskIdParams {
  taskId: number;
}

export type ExportTaskType =
  | ImportExportTaskType.QUERY_RESULT_EXPORT
  | ImportExportTaskType.SQL_EXPORT
  | ImportExportTaskType.TABLE_DATA_EXPORT;

export type ImportTaskType = ImportExportTaskType.DATA_FILE_IMPORT | ImportExportTaskType.SQL_FILE_IMPORT;

export interface ExportTaskParams extends IDatabaseBaseInfo {
  taskType: ExportTaskType;
  taskName?: string;
  tableNames?: string[];
  sql?: string;
  originalSql?: string;
  resultSetId?: number;
  exportSize?: string;
  format: ImportExportFileType;
  scope?: 'ALL' | 'SCHEMA' | 'TABLE';
  containData?: boolean;
  containsHeader?: boolean;
  exportPath?: string;
  suggestedFileName?: string;
}

export interface ImportTaskParams extends IDatabaseBaseInfo {
  taskType: ImportTaskType;
  taskName?: string;
  tableName?: string;
  sourceFile: string;
  displayFileName?: string;
  file?: File;
  format: ImportExportFileType;
  dataTimeFormat?: string;
}

const submitExport = createRequest<ExportTaskParams, TaskSubmissionResponse>('/api/tasks/export', { method: 'post' });
const submitImportByPath = createRequest<ImportTaskParams, TaskSubmissionResponse>('/api/tasks/import', {
  method: 'post',
});
const submitImportUpload = createRequest<{ file: File; request: Blob }, TaskSubmissionResponse>(
  '/api/tasks/import/upload',
  {
    method: 'post',
    contentType: 'formData',
  },
);

const submitImport = (params: ImportTaskParams) => {
  const transport = resolveImportTaskTransport(params, isDesktop);
  return transport.kind === 'upload'
    ? submitImportUpload(transport.params)
    : submitImportByPath(transport.params as ImportTaskParams);
};

const getTaskList = createRequest<TaskListParams, IPageResponse<ImportExportTaskDetails>>('/api/tasks/list', {
  method: 'get',
  errorLevel: false,
});
const getTaskDetails = createRequest<TaskIdParams, ImportExportTaskDetails>('/api/tasks/get', {
  method: 'get',
  errorLevel: false,
});
const getTaskEvents = createRequest<TaskEventListParams, ImportExportTaskEvent[]>('/api/tasks/events', {
  method: 'get',
  errorLevel: false,
});

const deleteTask = createRequest<TaskIdParams, void>('/api/tasks/delete', { method: 'delete' });
const getActiveTaskCount = createRequest<void, number>('/api/tasks/active-count', { method: 'get', errorLevel: false });
const prepareUserExit = createRequest<void, void>('/api/tasks/prepare-user-exit', {
  method: 'post',
  errorLevel: 'toast',
});
const abortUserExit = createRequest<void, void>('/api/tasks/abort-user-exit', {
  method: 'post',
  errorLevel: false,
});

// Generate Java classes
const generateJavaClass = createRequest<GenerateJavaClassParams, number>('/api/rdb/table/generate/class', {
  method: 'post',
});

export default {
  submitExport,
  submitImport,
  getTaskList,
  getTaskDetails,
  getTaskEvents,
  deleteTask,
  getActiveTaskCount,
  prepareUserExit,
  abortUserExit,
  generateJavaClass,
};
