import { IDatabaseBaseInfo } from '@/typings/database';
import importExportServices from '@/service/importExport';
import jcefApi from '@/jcef';
import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import { isDesktop } from '@/utils/env';

export interface ExportSqlFileProps extends IDatabaseBaseInfo {
  scope: 'ALL' | 'SCHEMA' | 'TABLE';
  tableNames?: string[];
  getTaskList?: () => void;
  openLogModal?: (taskId: number) => void;
}

export const handleExportSqlFile = async (props: ExportSqlFileProps) => {
  const exportPath = isDesktop ? await jcefApi.selectDirectory() : undefined;
  if (isDesktop && !exportPath) return;

  const { getTaskList, openLogModal, ...request } = props;
  const result = await importExportServices.submitExport({
    ...request,
    exportPath,
    taskType: ImportExportTaskType.SQL_EXPORT,
    format: ImportExportFileType.SQL,
    containsHeader: true,
  });
  getTaskList?.();
  openLogModal?.(result.taskId);
};
