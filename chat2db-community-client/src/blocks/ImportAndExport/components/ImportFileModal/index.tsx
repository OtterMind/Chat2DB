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
import ImportMappingContent from '@/blocks/ImportAndExport/components/ImportMappingContent';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import sqlService from '@/service/sql';
import { prepareWebImportParams } from './submission';
import {
  IMPORT_TARGET_TABLE_REFRESH_EVENT,
  shouldRefreshImportTargetTable,
} from '@/store/importExport/taskCenterUtils';
import type { FileUrl } from '@/components/UploadLocalFile';

interface IProps {
  className?: string;
}

const isPreviewFile = (file?: FileUrl) => {
  const name = (file?.fileName || file?.file?.name)?.toLowerCase();
  return name?.endsWith('.csv') || name?.endsWith('.xls') || name?.endsWith('.xlsx');
};

export default memo<IProps>((_props) => {
  const [isReady, setIsReady] = useState(false);
  const importExportFileRef = useRef<ImportExportFileRef>(null);
  const previousTaskDetailsRef = useRef<ImportExportTaskDetails>();
  const [taskId, setTaskId] = useState<number>();
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const [importFile, setImportFile] = useState<FileUrl>();

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
      previousTaskDetailsRef.current = undefined;
      setImportFile(undefined);
    }
  }, [importExportDataBoundInfo]);

  const handleRunSQl = async () => {
    const params = importExportFileRef.current?.getValues();
    if (!params) return;
    let response;
    if ('sourceFile' in params) {
      let importParams = params;
      if (!isDesktop) {
        if (!importFile?.file) return;
        importParams = await prepareWebImportParams(importParams, importFile.file, sqlService.uploadImportFile);
      }
      response = await importExportServices.submitImport(importParams);
    } else {
      response = await importExportServices.submitExport(params);
    }
    setTaskId(response.taskId);
    getTaskList();
  };

  const handleImportFileChange = (file?: FileUrl) => {
    setImportFile(file);
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
            <Button type="primary" disabled={!isReady} onClick={handleRunSQl}>
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
    const previousTask = previousTaskDetailsRef.current;
    previousTaskDetailsRef.current = _taskDetails;
    setTaskDetails(_taskDetails);
    if (shouldRefreshImportTargetTable(previousTask, _taskDetails)) {
      window.dispatchEvent(
        new CustomEvent(IMPORT_TARGET_TABLE_REFRESH_EVENT, {
          detail: _taskDetails.target,
        }),
      );
      void getTaskList();
    }
  };

  const importPreviewContext =
    importExportDataBoundInfo?.type === ImportExportType.IMPORT &&
    isPreviewFile(importFile) &&
    importExportDataBoundInfo.dataSourceId != null &&
    importExportDataBoundInfo.databaseName != null &&
    importFile != null
      ? {
          dataSourceId: importExportDataBoundInfo.dataSourceId,
          databaseName: importExportDataBoundInfo.databaseName,
          schemaName: importExportDataBoundInfo.schemaName,
          tableName: importExportDataBoundInfo.tableName || '',
          file: importFile,
        }
      : null;
  const showImportPreview = taskId == null && importPreviewContext != null;

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
      width={showImportPreview ? 960 : undefined}
      centered
      destroyOnClose
      footer={taskId ? logRenderFooter() : showImportPreview ? null : renderFooter()}
      maskClosable={false}
      onCancel={() => {
        setImportExportDataBoundInfo(null);
      }}
    >
      {taskId ? (
        <Log onTaskChange={handleTaskChange} taskId={taskId} />
      ) : importPreviewContext ? (
        <ImportMappingContent
          dataSourceId={importPreviewContext.dataSourceId}
          databaseName={importPreviewContext.databaseName}
          schemaName={importPreviewContext.schemaName}
          tableName={importPreviewContext.tableName}
          file={importPreviewContext.file}
          onSubmitted={(submittedTaskId) => {
            setTaskId(submittedTaskId);
            getTaskList();
          }}
        />
      ) : (
        <ImportExportFile
          ref={importExportFileRef}
          setIsReady={setIsReady}
          onImportFileChange={handleImportFileChange}
        />
      )}
    </Modal>
  );
});
