import createRequest from './base';
import { IDatabaseBaseInfo } from '@/typings/database';
import { IPageResponse } from '@/typings';
import type {
  IExcelOptions,
  IJsonOptions,
  ISqlImportOptions,
  ICsvOptions,
  ImportDuplicatePolicy,
  ImportExportTaskDetails,
  ImportExportTaskEvent,
  ImportFailurePolicy,
} from '@/typings/importExport';
import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';

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
  /** Cursor for the task center: return tasks older than this id. */
  beforeId?: number;
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
  /** CSV character set; absent keeps the UTF-8 default of the backend. */
  encoding?: string;
  /** CSV field delimiter; absent keeps the comma default of the backend. */
  delimiter?: string;
  exportPath?: string;
  suggestedFileName?: string;
}

export interface ImportTaskParams extends IDatabaseBaseInfo {
  taskType: ImportTaskType;
  taskName?: string;
  tableName?: string;
  sourceFile?: string;
  fileId?: string;
  displayFileName?: string;
  format: ImportExportFileType;
  dataTimeFormat?: string;
  csvOptions?: ICsvOptions;
  excelOptions?: IExcelOptions;
  jsonOptions?: IJsonOptions;
  sqlImportOptions?: ISqlImportOptions;
  mode?: 'FAST' | 'STANDARD';
  duplicatePolicy?: ImportDuplicatePolicy;
  failurePolicy?: ImportFailurePolicy;
}

/**
 * Wire shape of one stored import template. Normalization belongs to the import wizard, so the
 * fields stay unknown here and a malformed record cannot pretend to be a usable template.
 */
export interface ImportTemplateRecord {
  name?: unknown;
  createdAt?: unknown;
  /** {@code TaskFileFormat} name the preset belongs to; absent when the store cannot tell. */
  fileType?: unknown;
  formatOptions?: unknown;
}

/** Reads every preset, or only the presets of one file format. */
export interface ImportTemplateListParams {
  fileType?: string;
}

export interface ImportTemplateDeleteParams {
  name: string;
  /** File type of the preset, used when no readable document carries the name. */
  fileType?: string;
}

const submitExport = createRequest<ExportTaskParams, TaskSubmissionResponse>('/api/tasks/export', { method: 'post' });
const submitImport = createRequest<ImportTaskParams, TaskSubmissionResponse>('/api/tasks/import', { method: 'post' });

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

// Continues a stopped import as a new task from the checkpoint of the source task.
const resumeTask = createRequest<TaskIdParams, TaskSubmissionResponse>('/api/tasks/resume', { method: 'post' });
const deleteTask = createRequest<TaskIdParams, void>('/api/tasks/delete', { method: 'delete' });
// Reports whether the task was still cancellable: an already terminal task answers false, so the
// caller must not treat it as an error. The task center surfaces failures itself.
const cancelTask = createRequest<TaskIdParams, boolean>('/api/tasks/cancel', {
  method: 'post',
  errorLevel: false,
});
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

// Import mapping templates are one global list persisted in the backend user data directory, so no
// request is scoped to a data source, database, or table; the only scope they know is the file type
// of the preset. The wizard reports its own failures, which is why these calls keep their error
// surface off.
const listImportTemplates = createRequest<ImportTemplateListParams | void, ImportTemplateRecord[]>(
  '/api/rdb/import/templates',
  { method: 'get', errorLevel: false },
);
const saveImportTemplate = createRequest<ImportTemplateRecord, ImportTemplateRecord[]>(
  '/api/rdb/import/templates',
  { method: 'post', errorLevel: false },
);
const deleteImportTemplate = createRequest<ImportTemplateDeleteParams, ImportTemplateRecord[]>(
  '/api/rdb/import/templates',
  { method: 'delete', errorLevel: false },
);

export default {
  submitExport,
  submitImport,
  getTaskList,
  getTaskDetails,
  getTaskEvents,
  resumeTask,
  deleteTask,
  cancelTask,
  getActiveTaskCount,
  prepareUserExit,
  abortUserExit,
  generateJavaClass,
  listImportTemplates,
  saveImportTemplate,
  deleteImportTemplate,
};
