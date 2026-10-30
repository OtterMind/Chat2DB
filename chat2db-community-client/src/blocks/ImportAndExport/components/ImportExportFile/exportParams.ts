import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import type { ExportTaskParams } from '@/service/importExport';
import type { ImportExportDataBoundInfo } from '@/typings/importExport';

export interface ExportFormValue {
  exportType: ImportExportFileType;
  containsHeader: boolean;
  /** CSV only: the character set of the file; other formats ignore it. */
  encoding?: string;
  /** CSV only: the field delimiter of the file; other formats ignore it. */
  delimiter?: string;
  fileUrl?: string;
}

export const DEFAULT_EXPORT_FORM_VALUE: ExportFormValue = {
  exportType: ImportExportFileType.CSV,
  containsHeader: true,
  encoding: 'UTF-8',
  delimiter: ',',
};

/** Maps the export wizard form value onto the request body the backend reads (ExportTaskSpec). */
export const buildExportTaskParams = (
  boundInfo: ImportExportDataBoundInfo,
  formValue: ExportFormValue,
  exportLocation: string,
): ExportTaskParams => ({
  dataSourceId: boundInfo.dataSourceId,
  databaseName: boundInfo.databaseName,
  schemaName: boundInfo.schemaName,
  format: formValue.exportType,
  taskType: ImportExportTaskType.TABLE_DATA_EXPORT,
  tableNames: [boundInfo.tableName],
  containsHeader: formValue.containsHeader,
  // The backend only reads these for a CSV export; sending them elsewhere would claim options the
  // other writers never use.
  ...(formValue.exportType === ImportExportFileType.CSV
    ? { encoding: formValue.encoding || 'UTF-8', delimiter: formValue.delimiter || ',' }
    : {}),
  exportPath: exportLocation || formValue.fileUrl,
});
