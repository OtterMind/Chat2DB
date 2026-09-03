import { memo, useEffect, useRef, useState } from 'react';
import { Modal, IconfontSvg } from '@chat2db/ui';
import { Button } from 'antd';
import i18n from '@/i18n';
import ImportExportFile, { ImportExportFileRef } from '../ImportExportFile';
import { useImportExportStore } from '@/store/importExport';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices from '@/service/importExport';
import { ImportExportTaskStatus, ImportExportType } from '@/constants/importExport';
import Log from '@/blocks/ImportAndExport/components/Log';
import { ImportExportTaskDetails } from '@/typings/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { createPendingSubmissionGuard } from '../../submissionGuard';

interface IProps {
  className?: string;
}

export default memo<IProps>((_props) => {
  const [isReady, setIsReady] = useState(false);
  const importExportFileRef = useRef<ImportExportFileRef>(null);
  const [taskId, setTaskId] = useState<number>();
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submissionGuard = useRef(createPendingSubmissionGuard()).current;

  const { importExportDataBoundInfo, setImportExportDataBoundInfo, getTaskList } = useImportExportStore((state) => {
    return {
      importExportDataBoundInfo: state.importExportDataBoundInfo,
      setImportExportDataBoundInfo: state.setImportExportDataBoundInfo,
      getTaskList: state.getTaskList,
    };
  });

  useEffect(() => {
    if (!importExportDataBoundInfo) {
      setTaskId(undefined);
      setTaskDetails(undefined);
    }
  }, [importExportDataBoundInfo]);

  const handleRunSQl = () => {
    const params = importExportFileRef.current?.getValues();
    if (!params) return;
    const submission = submissionGuard.run(async () => {
      setIsSubmitting(true);
      try {
        const response =
          'sourceFile' in params
            ? await importExportServices.submitImport(params)
            : await importExportServices.submitExport(params);
        setTaskId(response.taskId);
        getTaskList();
      } finally {
        setIsSubmitting(false);
      }
    });
    void submission?.catch(() => undefined);
  };

  const renderFooter = () => {
    return (
      <ModalFooterButton
        footerRight={
          <>
            <Button
              disabled={isSubmitting}
              onClick={() => {
                setImportExportDataBoundInfo(null);
              }}
            >
              {i18n('common.button.cancel')}
            </Button>
            <Button type="primary" loading={isSubmitting} disabled={!isReady || isSubmitting} onClick={handleRunSQl}>
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
    setTaskDetails(_taskDetails);
  };

  return (
    <Modal
      open={!!importExportDataBoundInfo}
      okText={i18n('common.button.start')}
      cancelText={i18n('common.button.cancel')}
      title={
        importExportDataBoundInfo?.type === ImportExportType.IMPORT
          ? i18n('workspace.menu.importData')
          : i18n('workspace.menu.exportData')
      }
      headerIconCode={importExportDataBoundInfo?.type === ImportExportType.IMPORT ? 'icon-upload' : 'icon-download'}
      headerBorder
      destroyOnClose
      footer={taskId ? logRenderFooter() : renderFooter()}
      maskClosable={false}
      onCancel={() => {
        if (!isSubmitting) {
          setImportExportDataBoundInfo(null);
        }
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
