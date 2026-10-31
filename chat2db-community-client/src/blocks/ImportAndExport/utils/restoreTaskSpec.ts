import { ImportExportFileType, ImportExportTaskType, ImportExportType } from '@/constants/importExport';
import type {
  ImportDuplicatePolicy,
  ImportExportDataBoundInfo,
  ImportExportTaskDetails,
  ImportFailurePolicy,
  ISqlImportOptions,
} from '@/typings/importExport';

/**
 * Pure mapping from a task's `specJson` back onto the import/export wizard.
 *
 * The backend stores the raw import/export parameter snapshot (the serialized import/export task
 * spec). The domain layout is the primary shape:
 *
 *   { "taskType": "DATA_FILE_IMPORT", "target": { "dataSourceId": 1, "databaseName": "db",
 *     "schemaName": "public", "tableName": "orders" }, "sourceFile": "/tmp/orders.csv",
 *     "displayFileName": "orders.csv", "format": "CSV", "sqlImportOptions": { "encoding": "UTF-8" },
 *     "csvOptions": { ... }, "columnMappings": [ ... ], "mode": "FAST", "duplicatePolicy": "INSERT" }
 *
 * The flat request layout (`dataSourceId`/`databaseName`/`tableName` next to `format`, plus
 * `tableNames` for exports) is accepted as a compatibility branch because the writer of
 * `specJson` lives in a separate change. Unknown fields are ignored on purpose: the wizard only
 * consumes the fields it owns, and the backend may add more.
 *
 * Everything here is pure — no store, no i18n call, no network — so the UI and the tests can both
 * use the structured result.
 */

/** Why a task configuration cannot seed the wizard. */
export type RestoreTaskSpecFailureReason = 'EMPTY_SPEC' | 'INVALID_JSON' | 'MISSING_TARGET' | 'UNSUPPORTED_SPEC';

/**
 * Literal i18n keys: the UI calls i18n(key) directly, and the literal types make the i18n layer
 * type-check that every message exists in the locale modules.
 */
export type RestoreFailureI18nKey =
  | 'workspace.task.reuse.unavailable'
  | 'workspace.task.reuse.invalidSpec'
  | 'workspace.task.reuse.missingTarget'
  | 'workspace.task.reuse.unsupportedSpec';

export type RestoreNoticeI18nKey =
  | 'workspace.importExport.restoredSourceFile'
  | 'workspace.importExport.restoredSourceFileUnknown'
  | 'workspace.importExport.restoredFormatOptions'
  | 'workspace.importExport.restoredImportPolicies';

export interface RestoreTaskSpecFailure {
  ok: false;
  reason: RestoreTaskSpecFailureReason;
  /** i18n key for the user-facing explanation; the UI only calls i18n(key). */
  i18nKey: RestoreFailureI18nKey;
}

/** Extra information the wizard must show; the restored fields alone cannot carry it. */
export interface RestoreNotice {
  code: 'SOURCE_FILE' | 'SOURCE_FILE_UNKNOWN' | 'FORMAT_OPTIONS' | 'IMPORT_POLICIES';
  i18nKey: RestoreNoticeI18nKey;
  params?: string[];
}

/** Wizard form fields the prefill can seed (a subset of the wizard's ExportFormValue). */
export interface RestoredWizardFormValue {
  exportType?: ImportExportFileType;
  containsHeader?: boolean;
  fileUrl?: string;
}

export interface RestoredTaskSpec {
  type: ImportExportType;
  /** Bound target; set through `setImportExportDataBoundInfo` to open the wizard. */
  boundInfo: ImportExportDataBoundInfo;
  formValue: RestoredWizardFormValue;
  /** SQL import encoding, when the original task imported a .sql file. */
  sqlImportOptions?: ISqlImportOptions;
  /**
   * Execution-affecting policies of the original import. They have no wizard field, so the wizard
   * re-sends them unchanged when the user starts the restored configuration.
   */
  importPolicies?: { duplicatePolicy?: ImportDuplicatePolicy; failurePolicy?: ImportFailurePolicy };
  /** Desktop export directory the user had chosen; never applied on web. */
  exportPath?: string;
  notices: RestoreNotice[];
}

export type RestoreTaskSpecResult = { ok: true; restore: RestoredTaskSpec } | RestoreTaskSpecFailure;

const RESTORE_FAILURE_I18N_KEYS: Record<RestoreTaskSpecFailureReason, RestoreFailureI18nKey> = {
  EMPTY_SPEC: 'workspace.task.reuse.unavailable',
  INVALID_JSON: 'workspace.task.reuse.invalidSpec',
  MISSING_TARGET: 'workspace.task.reuse.missingTarget',
  UNSUPPORTED_SPEC: 'workspace.task.reuse.unsupportedSpec',
};

const IMPORT_TASK_TYPES = [ImportExportTaskType.DATA_FILE_IMPORT, ImportExportTaskType.SQL_FILE_IMPORT];
const EXPORT_TASK_TYPES = [
  ImportExportTaskType.TABLE_DATA_EXPORT,
  ImportExportTaskType.SQL_EXPORT,
  ImportExportTaskType.QUERY_RESULT_EXPORT,
];
const KNOWN_FILE_FORMATS = Object.values(ImportExportFileType) as string[];

type JsonRecord = Record<string, unknown>;

const asRecord = (value: unknown): JsonRecord | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as JsonRecord) : undefined;

const firstString = (...values: unknown[]): string | undefined => {
  for (const value of values) {
    if (typeof value === 'string' && value.trim() !== '') return value;
  }
  return undefined;
};

const asNumber = (value: unknown): number | undefined => {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) return Number(value);
  return undefined;
};

const asBoolean = (value: unknown): boolean | undefined => (typeof value === 'boolean' ? value : undefined);

const fail = (reason: RestoreTaskSpecFailureReason): RestoreTaskSpecFailure => ({
  ok: false,
  reason,
  i18nKey: RESTORE_FAILURE_I18N_KEYS[reason],
});

/** Cheap check for the task center: legacy tasks have no usable snapshot. */
export const hasSavedTaskSpec = (task: Pick<ImportExportTaskDetails, 'specJson'>): boolean =>
  typeof task.specJson === 'string' && task.specJson.trim() !== '';

const restoreTarget = (spec: JsonRecord, task: Pick<ImportExportTaskDetails, 'target'>) => {
  const specTarget = asRecord(spec.target) ?? {};
  const taskTarget = task.target ?? {};
  return {
    dataSourceId: asNumber(specTarget.dataSourceId ?? spec.dataSourceId ?? taskTarget.dataSourceId),
    databaseName: firstString(specTarget.databaseName, spec.databaseName, taskTarget.databaseName),
    schemaName: firstString(specTarget.schemaName, spec.schemaName, taskTarget.schemaName),
    tableName: firstString(specTarget.tableName, spec.tableName, taskTarget.tableName),
  };
};

const restoreFileFormat = (value: unknown): ImportExportFileType | undefined => {
  const format = firstString(value);
  return format && KNOWN_FILE_FORMATS.includes(format) ? (format as ImportExportFileType) : undefined;
};

const restoreSqlImportOptions = (value: unknown): ISqlImportOptions | undefined => {
  const options = asRecord(value);
  const encoding = firstString(options?.encoding);
  return encoding ? { encoding } : undefined;
};

const sourceFileName = (sourceFile?: string): string | undefined => {
  if (!sourceFile) return undefined;
  const segments = sourceFile.split(/[\\/]/).filter(Boolean);
  return segments.length ? segments[segments.length - 1] : undefined;
};

/**
 * Maps a task onto wizard state, or explains why it cannot be reused.
 * Never throws: malformed snapshots come back as a structured failure.
 */
export const parseTaskSpecForWizard = (
  task: Pick<ImportExportTaskDetails, 'type' | 'specJson' | 'target'>,
): RestoreTaskSpecResult => {
  if (!hasSavedTaskSpec(task)) return fail('EMPTY_SPEC');

  let parsed: unknown;
  try {
    parsed = JSON.parse(task.specJson as string);
  } catch {
    return fail('INVALID_JSON');
  }
  const spec = asRecord(parsed);
  if (!spec) return fail('INVALID_JSON');

  // The snapshot is authoritative; the task row is the fallback when a field is absent.
  const taskType = firstString(spec.taskType, spec.type, task.type);
  const knownTaskTypes = [...IMPORT_TASK_TYPES, ...EXPORT_TASK_TYPES];
  if (!taskType || !knownTaskTypes.includes(taskType as ImportExportTaskType)) {
    return fail('UNSUPPORTED_SPEC');
  }
  const isImport = IMPORT_TASK_TYPES.includes(taskType as ImportExportTaskType);

  const target = restoreTarget(spec, task);
  if (target.dataSourceId === undefined || !target.databaseName) return fail('MISSING_TARGET');

  const boundInfoBase = {
    dataSourceId: target.dataSourceId,
    databaseName: target.databaseName,
    schemaName: target.schemaName,
  };
  const format = restoreFileFormat(spec.format);

  if (isImport) {
    if (taskType === ImportExportTaskType.SQL_FILE_IMPORT) {
      // The wizard can only submit a SQL file import together with the source file; keep the format
      // on the restored form so the SQL options panel is the one the user sees.
      return {
        ok: true,
        restore: {
          type: ImportExportType.IMPORT,
          boundInfo: { ...boundInfoBase, tableName: target.tableName ?? '', type: ImportExportType.IMPORT },
          formValue: { exportType: format ?? ImportExportFileType.SQL },
          sqlImportOptions: restoreSqlImportOptions(spec.sqlImportOptions),
          importPolicies: restoreImportPolicies(spec),
          notices: buildImportNotices(spec),
        },
      };
    }
    return {
      ok: true,
      restore: {
        type: ImportExportType.IMPORT,
        boundInfo: { ...boundInfoBase, tableName: target.tableName ?? '', type: ImportExportType.IMPORT },
        formValue: { exportType: format },
        importPolicies: restoreImportPolicies(spec),
        notices: buildImportNotices(spec),
      },
    };
  }

  if (taskType !== ImportExportTaskType.TABLE_DATA_EXPORT) {
    // Query-result and raw-SQL exports need a live result set or SQL text the wizard cannot hold.
    return fail('UNSUPPORTED_SPEC');
  }

  const tableNames = Array.isArray(spec.tableNames)
    ? spec.tableNames.filter((name): name is string => typeof name === 'string' && name.trim() !== '')
    : [];
  const scope = firstString(spec.scope);
  if (tableNames.length > 1 || (scope && scope !== 'TABLE')) return fail('UNSUPPORTED_SPEC');

  const tableName = tableNames[0] ?? target.tableName;
  if (!tableName) return fail('MISSING_TARGET');

  return {
    ok: true,
    restore: {
      type: ImportExportType.EXPORT,
      boundInfo: { ...boundInfoBase, tableName, type: ImportExportType.EXPORT },
      formValue: { exportType: format, containsHeader: asBoolean(spec.containsHeader) },
      exportPath: firstString(spec.exportPath),
      notices: [],
    },
  };
};

const restoreImportPolicies = (spec: JsonRecord) => {
  const duplicatePolicy = firstString(spec.duplicatePolicy) as ImportDuplicatePolicy | undefined;
  const failurePolicy = firstString(spec.failurePolicy) as ImportFailurePolicy | undefined;
  return duplicatePolicy || failurePolicy ? { duplicatePolicy, failurePolicy } : undefined;
};

const buildImportNotices = (spec: JsonRecord): RestoreNotice[] => {
  const sourceFile = firstString(spec.sourceFile);
  const fileName = firstString(spec.displayFileName) ?? sourceFileName(sourceFile);
  const notices: RestoreNotice[] = [
    fileName
      ? { code: 'SOURCE_FILE', i18nKey: 'workspace.importExport.restoredSourceFile', params: [fileName] }
      : { code: 'SOURCE_FILE_UNKNOWN', i18nKey: 'workspace.importExport.restoredSourceFileUnknown' },
  ];
  // The wizard's preview step re-reads the delimiter, encoding, sheet, json path and column mapping
  // from the selected file, so those options cannot be carried over.
  const hasFileDerivedOptions =
    asRecord(spec.csvOptions) ||
    asRecord(spec.excelOptions) ||
    asRecord(spec.jsonOptions) ||
    Array.isArray(spec.columnMappings);
  if (hasFileDerivedOptions) {
    notices.push({ code: 'FORMAT_OPTIONS', i18nKey: 'workspace.importExport.restoredFormatOptions' });
  }
  if (restoreImportPolicies(spec)) {
    notices.push({ code: 'IMPORT_POLICIES', i18nKey: 'workspace.importExport.restoredImportPolicies' });
  }
  return notices;
};
