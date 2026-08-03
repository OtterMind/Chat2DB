import { IDatabaseBaseInfo } from '@/typings/database';
import importExportServices from '@/service/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { downloadHttpAttachment, selectHostExportPath } from '@/utils/hostFileTransfer';

export interface ExportSqlFileProps extends IDatabaseBaseInfo {
  tableNames?: string[];
}

export const generateJavaClass = async (props: ExportSqlFileProps) => {
  const exportPath = await selectHostExportPath(isDesktop, jcefApi.selectDirectory);
  if (exportPath === undefined) return;

  const params = {
    ...props,
    exportPath,
  };

  if (isDesktop) {
    void importExportServices.generateJavaClass(params);
    return;
  }
  void downloadHttpAttachment('/api/rdb/table/generate/class', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/zip',
    },
    body: JSON.stringify(params),
  }).catch((error) => {
    console.error('Failed to download generated Java classes:', error);
  });
};
