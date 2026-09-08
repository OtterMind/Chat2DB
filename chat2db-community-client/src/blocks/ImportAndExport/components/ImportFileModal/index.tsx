import { memo, useEffect, useRef, useState } from 'react';
import { Modal, IconfontSvg } from '@chat2db/ui';
import { Button } from 'antd';
import i18n from '@/i18n';
import ImportExportFile, { ImportExportFileRef } from '../ImportExportFile';
import { useImportExportStore } from '@/store/importExport';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices, { type ExportTaskParams, type ImportTaskParams } from '@/service/importExport';
import { ImportExportTaskStatus, ImportExportType } from '@/constants/importExport';
import Log from '@/blocks/ImportAndExport/components/Log';
import { ImportExportTaskDetails } from '@/typings/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import {
  IMPORT_TARGET_TABLE_REFRESH_EVENT,
  shouldRefreshImportTargetTable,
} from '@/store/importExport/taskCenterUtils';

interface IProps {
  className?: string;
}

export default memo<IProps>((_props) => {
  const [isReady, setIsReady] = useState(false);
  const importExportFileRef = useRef<ImportExportFileRef>(null);
  const [taskId, setTaskId] = useState<number>();
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const previousTaskDetailsRef = useRef<ImportExportTaskDetails>();
  const [submitting, setSubmitting] = useState(false);

  const { importExportDataBoundInfo, setImportExportDataBoundInfo, getTaskList } = useImportExportStore((state) => {
    return {
      importExportDataBoundInfo: state.importExportDataBoundInfo,
      setImportExportDataBoundInfo: state.setImportExportDataBoundInfo,
      getTaskList: state.getTaskList,
    };
  });

  useEffect(() => {
    if (!importExportDataBoundInfo) {
      setIsReady(false);
      setTaskId(undefined);
      setTaskDetails(undefined);
      previousTaskDetailsRef.current = undefined;
    }
  }, [importExportDataBoundInfo]);

  const handleRunSQl = () => {
    if (submitting) return;
    const params = importExportFileRef.current?.getValues();
    if (!params) return;
    setSubmitting(true);
    const request =
      params.taskType === 'DATA_FILE_IMPORT' || params.taskType === 'SQL_FILE_IMPORT'
        ? importExportServices.submitImport(params as ImportTaskParams)
        : importExportServices.submitExport(params as ExportTaskParams);
    request
      .then((res) => {
        setTaskId(res.taskId);
        getTaskList();
      })
      .catch(() => {})
      .finally(() => setSubmitting(false));
  };

  const renderFooter = () => {
    return (
      <ModalFooterButton
        footerRight={
          <>
            <Button
              onClick={() => {
                setImportExportDataBoundInfo(null);
              }}
            >
              {i18n('common.button.cancel')}
            </Button>
            <Button type="primary" disabled={!isReady} loading={submitting} onClick={handleRunSQl}>
              {i18n('common.button.start')}
            </Button>
          </>
        }
      />
    );
  };

  const handleOpenFile = () => {
    if (!taskDetails?.artifactId) return;
    if (isDesktop) {
      jcefApi.revealInExplorer(taskDetails.artifactId);
      return;
    }
    window.open(`/api/tasks/artifact?taskId=${taskDetails.id}`, '_blank');
  };

  const logRenderFooter = () => (
    <ModalFooterButton
      footerLeft={
        <>
          {importExportDataBoundInfo?.type === ImportExportType.EXPORT &&
            taskDetails?.status === ImportExportTaskStatus.SUCCESS && (
              <Button icon={<IconfontSvg code="icon-folder" />} onClick={handleOpenFile}>
                {i18n('workspace.text.openFile')}
              </Button>
            )}
        </>
      }
      footerRight={
        <>
          <Button
            onClick={() => {
              setImportExportDataBoundInfo(null);
            }}
          >
            {i18n('common.button.close')}
          </Button>
        </>
      }
    />
  );

  const handleTaskChange = (_taskDetails: ImportExportTaskDetails) => {
    const previous = previousTaskDetailsRef.current;
    previousTaskDetailsRef.current = _taskDetails;
    setTaskDetails(_taskDetails);
    if (shouldRefreshImportTargetTable(previous, _taskDetails)) {
      window.dispatchEvent(new CustomEvent(IMPORT_TARGET_TABLE_REFRESH_EVENT, { detail: _taskDetails.target }));
      void getTaskList();
    }
  };

  const modalTitle = (() => {
    if (importExportDataBoundInfo?.type === ImportExportType.IMPORT) {
      return importExportDataBoundInfo.targetScope === 'TABLE'
        ? i18n('workspace.menu.importData')
        : i18n('workspace.menu.runSqlFile');
    }
    if (importExportDataBoundInfo?.sqlExportScope === 'SCHEMA') {
      return i18n('workspace.menu.exportStructure');
    }
    if (importExportDataBoundInfo?.sqlExportScope === 'ALL') {
      return i18n('workspace.menu.exportStructureData');
    }
    return i18n('workspace.menu.exportData');
  })();

  return (
    <Modal
      open={!!importExportDataBoundInfo}
      okText={i18n('common.button.start')}
      cancelText={i18n('common.button.cancel')}
      title={modalTitle}
      width={960}
      headerIconCode={importExportDataBoundInfo?.type === ImportExportType.IMPORT ? 'icon-upload' : 'icon-download'}
      headerBorder
      destroyOnClose
      footer={taskId ? logRenderFooter() : renderFooter()}
      maskClosable={false}
      onCancel={() => {
        setImportExportDataBoundInfo(null);
      }}
    >
      {taskId ? (
        <Log onTaskChange={handleTaskChange} taskId={taskId} />
      ) : (
        <ImportExportFile ref={importExportFileRef} setIsReady={setIsReady} />
      )}
    </Modal>
  );
});
