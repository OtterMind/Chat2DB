import { DatabaseTypeCode } from '@/constants/common';
import { ImportExportTaskStatus } from '@/constants/importExport';
import { OperationColumn } from '@/constants/tree';

const MYSQL_TABLE_MAINTENANCE_OPERATIONS: readonly OperationColumn[] = [
  OperationColumn.AnalyzeTable,
  OperationColumn.OptimizeTable,
  OperationColumn.CheckTable,
  OperationColumn.RepairTable,
] as const;

const MYSQL_REPAIR_ENGINES = new Set(['MYISAM', 'ARCHIVE', 'CSV']);

export function canShowTableMaintenanceOperation(
  operation: OperationColumn,
  databaseType?: DatabaseTypeCode,
  engine?: string,
): boolean {
  if (!MYSQL_TABLE_MAINTENANCE_OPERATIONS.includes(operation)) {
    return true;
  }
  if (databaseType !== DatabaseTypeCode.MYSQL) {
    return false;
  }
  if (operation !== OperationColumn.RepairTable) {
    return true;
  }
  return !!engine && MYSQL_REPAIR_ENGINES.has(engine.trim().toUpperCase());
}

export function getSupportedTableMaintenanceOperations(
  databaseType?: DatabaseTypeCode,
  engine?: string,
): OperationColumn[] {
  return MYSQL_TABLE_MAINTENANCE_OPERATIONS.filter((operation) =>
    canShowTableMaintenanceOperation(operation, databaseType, engine),
  );
}

const TABLE_MAINTENANCE_TERMINAL_TASK_STATUSES = new Set([
  ImportExportTaskStatus.SUCCESS,
  ImportExportTaskStatus.FAILED,
  ImportExportTaskStatus.CANCELLED,
]);

interface TableMaintenanceTaskStatus {
  status?: ImportExportTaskStatus;
}

type TableMaintenanceTaskLoader = (params: { taskId: number }) => Promise<TableMaintenanceTaskStatus>;

export function isTableMaintenanceTaskTerminal(status?: ImportExportTaskStatus): boolean {
  return status !== undefined && TABLE_MAINTENANCE_TERMINAL_TASK_STATUSES.has(status);
}

export async function refreshAfterTableMaintenanceTaskCompletes(
  taskId: number,
  loadTask: TableMaintenanceTaskLoader,
  refresh: () => void,
  pollInterval = 1000,
  maxAttempts = 3600,
): Promise<void> {
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    try {
      const task = await loadTask({ taskId });
      if (isTableMaintenanceTaskTerminal(task.status)) {
        refresh();
        return;
      }
    } catch {
      // Retry transient task-detail failures within the bounded polling window.
    }
    await new Promise((resolve) => setTimeout(resolve, pollInterval));
  }
  refresh();
}
