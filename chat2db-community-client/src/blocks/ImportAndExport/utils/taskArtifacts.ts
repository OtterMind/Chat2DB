import { ImportExportTaskStatus, ImportExportTaskType } from '@/constants/importExport';
import type { ImportExportTaskDetails } from '@/typings/importExport';

/**
 * Task artifacts, resume eligibility, and the progress numbers of a task.
 *
 * The progress line must not be scraped out of the log text: the row counter comes from the
 * structured import checkpoint the backend persists (`processedRows`), and the rate is derived
 * from that counter and the persisted timestamps.
 */

const hasText = (value?: string | null): boolean => typeof value === 'string' && value.trim() !== '';

/** Web download endpoints; the desktop build reveals the local artifact path instead. */
export const buildTaskArtifactUrl = (taskId: number): string => `/api/tasks/artifact?taskId=${taskId}`;
export const buildTaskErrorArtifactUrl = (taskId: number): string =>
  `/api/tasks/error-artifact?taskId=${taskId}`;

export const isImportTaskType = (type?: ImportExportTaskType | string | null): boolean =>
  type === ImportExportTaskType.DATA_FILE_IMPORT || type === ImportExportTaskType.SQL_FILE_IMPORT;

/** Only a stopped import that stored both its spec and its checkpoint can continue where it stopped. */
export const canResumeTask = (
  task: Pick<ImportExportTaskDetails, 'type' | 'status' | 'specJson' | 'checkpointJson'>,
): boolean =>
  isImportTaskType(task.type) &&
  (task.status === ImportExportTaskStatus.FAILED || task.status === ImportExportTaskStatus.CANCELLED) &&
  hasText(task.specJson) &&
  hasText(task.checkpointJson);

/** The rejected-rows file is offered exactly when the task published one. */
export const canDownloadErrorArtifact = (
  task: Pick<ImportExportTaskDetails, 'errorArtifactId'>,
): boolean => hasText(task.errorArtifactId);

/** Reads the structured row counter of the import checkpoint; null when there is none. */
export const parseProcessedRows = (checkpointJson?: string | null): number | null => {
  if (!hasText(checkpointJson)) {
    return null;
  }
  try {
    const parsed = JSON.parse(checkpointJson as string) as { processedRows?: unknown };
    const rows = Number(parsed?.processedRows);
    return Number.isFinite(rows) && rows >= 0 ? rows : null;
  } catch {
    return null;
  }
};

const toTimestamp = (value?: number | string | null): number | null => {
  if (value === null || value === undefined) {
    return null;
  }
  const timestamp = typeof value === 'number' ? value : Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : null;
};

const isCount = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0;

export interface TaskProgressSummary {
  processedRows: number;
  totalRows: number | null;
  /** Null while the elapsed time is unknown or too short to be meaningful. */
  rowsPerSecond: number | null;
  etaSeconds: number | null;
}

/**
 * Builds the one-line progress summary of a task. Structured backend fields win; legacy tasks fall
 * back to the persisted import checkpoint and the timestamps.
 */
export const buildTaskProgressSummary = (input: {
  processedRows?: number | null;
  totalRows?: number | null;
  rowsPerSecond?: number | null;
  etaSeconds?: number | null;
  checkpointJson?: string | null;
  startedAt?: number | string | null;
  finishedAt?: number | string | null;
  status?: ImportExportTaskStatus | string | null;
  now: number;
}): TaskProgressSummary | null => {
  const structured = isCount(input.processedRows);
  const processedRows = structured ? (input.processedRows as number) : parseProcessedRows(input.checkpointJson);
  if (processedRows === null) {
    return null;
  }
  const totalRows = isCount(input.totalRows) ? input.totalRows : null;
  if (structured) {
    return {
      processedRows,
      totalRows,
      rowsPerSecond: isCount(input.rowsPerSecond) ? input.rowsPerSecond : null,
      etaSeconds: isCount(input.etaSeconds) ? input.etaSeconds : null,
    };
  }
  const startedAt = toTimestamp(input.startedAt);
  const isActive =
    input.status === ImportExportTaskStatus.RUNNING || input.status === ImportExportTaskStatus.PENDING;
  const reference = isActive ? input.now : toTimestamp(input.finishedAt) ?? input.now;
  const elapsedSeconds = startedAt === null ? null : (reference - startedAt) / 1000;
  const rowsPerSecond = elapsedSeconds && elapsedSeconds > 0 ? processedRows / elapsedSeconds : null;
  const etaSeconds =
    rowsPerSecond !== null && rowsPerSecond > 0 && totalRows !== null
      ? Math.max(0, Math.ceil((totalRows - processedRows) / rowsPerSecond))
      : null;
  return { processedRows, totalRows, rowsPerSecond, etaSeconds };
};

/** One decimal below ten rows per second, none above it: "3.2 rows/s" but "120 rows/s". */
export const formatRowsPerSecond = (rowsPerSecond: number): string =>
  rowsPerSecond >= 10 ? String(Math.round(rowsPerSecond)) : rowsPerSecond.toFixed(1);

/** Compact remaining time: "42s", "3m 5s", "2h 10m". */
export const formatEta = (seconds: number): string => {
  const total = Math.max(0, Math.round(seconds));
  if (total < 60) {
    return `${total}s`;
  }
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  if (minutes < 60) {
    return rest ? `${minutes}m ${rest}s` : `${minutes}m`;
  }
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return remainingMinutes ? `${hours}h ${remainingMinutes}m` : `${hours}h`;
};
