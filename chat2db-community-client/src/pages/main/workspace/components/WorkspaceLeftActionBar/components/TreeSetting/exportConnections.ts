import ConnectionServer from '@/service/connection';
import feedback from '@/utils/feedback';
import {saveFileToDesktop} from '@/utils/file';
import i18n from '@/i18n';
import { runExportConnections } from './exportConnectionsFlow';

const EXPORT_CONNECTIONS_FEEDBACK_KEY = 'export-connections';

//#region 导出连接反馈

export const exportConnections = (params: { datasourceIds: number[] | null }) => {
  return runExportConnections(params, {
    exportDataSource: ConnectionServer.exportDataSource,
    saveFile: saveFileToDesktop,
    onExporting: () => {
      feedback.open({
        key: EXPORT_CONNECTIONS_FEEDBACK_KEY,
        type: 'loading',
        content: i18n('connection.export.exporting'),
        duration: 0,
      });
    },
    onSaved: () => {
      feedback.open({
        key: EXPORT_CONNECTIONS_FEEDBACK_KEY,
        type: 'success',
        content: i18n('connection.export.success'),
      });
    },
    onCancelled: () => {
      feedback.open({
        key: EXPORT_CONNECTIONS_FEEDBACK_KEY,
        type: 'info',
        content: i18n('connection.export.cancelled'),
      });
    },
    onFailed: () => {
      feedback.open({
        key: EXPORT_CONNECTIONS_FEEDBACK_KEY,
        type: 'error',
        content: i18n('connection.export.failed'),
      });
    },
  });
};

//#endregion
