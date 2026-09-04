import { memo, useEffect, useRef, useState } from 'react';
import { Modal, IconfontSvg } from '@chat2db/ui';
import { Button } from 'antd';
import i18n from '@/i18n';
import { TreeNodeType } from '@/constants';
import ImportExportFile, { ImportExportFileRef } from '../ImportExportFile';
import { useImportExportStore } from '@/store/importExport';
import { useTreeStore } from '@/store/tree';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices from '@/service/importExport';
import { ImportExportTaskStatus, ImportExportType } from '@/constants/importExport';
import Log from '@/blocks/ImportAndExport/components/Log';
import { ImportExportTaskDetails } from '@/typings/importExport';
import ImportMappingContent from '@/blocks/ImportAndExport/components/ImportMappingContent';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import type { FileUrl } from '@/components/UploadLocalFile';

interface IProps {
  className?: string;
}

const isPreviewFile = (file?: FileUrl) => {
  const name = file?.file?.name?.toLowerCase();
  return name?.endsWith('.xls') || name?.endsWith('.xlsx');
};

export default memo<IProps>((_props) => {
  const [isReady, setIsReady] = useState(false);
  const importExportFileRef = useRef<ImportExportFileRef>(null);
  const [taskId, setTaskId] = useState<number>();
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const [importFile, setImportFile] = useState<FileUrl>();
  const refreshedImportTaskIdsRef = useRef(new Set<number>());

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
      setImportFile(undefined);
      refreshedImportTaskIdsRef.current.clear();
    }
  }, [importExportDataBoundInfo]);

  const handleRunSQl = () => {
    const params = importExportFileRef.current?.getValues();
    if (!params) return;
    const request =
      'sourceFile' in params ? importExportServices.submitImport(params) : importExportServices.submitExport(params);
    request.then((res) => {
      setTaskId(res.taskId);
      getTaskList();
    });
  };

  const handleImportFileChange = (file: FileUrl) => {
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
    setTaskDetails(_taskDetails);
    if (
      importExportDataBoundInfo?.type === ImportExportType.IMPORT &&
      _taskDetails.status === ImportExportTaskStatus.SUCCESS &&
      !refreshedImportTaskIdsRef.current.has(_taskDetails.id)
    ) {
      refreshedImportTaskIdsRef.current.add(_taskDetails.id);
      const target = _taskDetails.target || importExportDataBoundInfo;
      const dataSourceId = target.dataSourceId ?? importExportDataBoundInfo.dataSourceId;
      const databaseName = target.databaseName ?? importExportDataBoundInfo.databaseName;
      const tableName = target.tableName ?? importExportDataBoundInfo.tableName;
      const { databaseType } = importExportDataBoundInfo;
      if (dataSourceId === undefined || !databaseName || !tableName || !databaseType) {
        return;
      }
      void useTreeStore.getState().refreshTreeNodeDataInBackground({
        treeNodeType: TreeNodeType.TABLE,
        dataSourceId,
        databaseName,
        schemaName: target.schemaName,
        tableName,
        databaseType,
      });
    }
  };

  const renderImportMappingContent = () => {
    if (
      importExportDataBoundInfo?.type !== ImportExportType.IMPORT ||
      isDesktop ||
      !isPreviewFile(importFile) ||
      !importFile?.file ||
      importExportDataBoundInfo.dataSourceId === undefined ||
      !importExportDataBoundInfo.databaseName
    ) {
      return null;
    }
    return (
      <ImportMappingContent
        dataSourceId={importExportDataBoundInfo.dataSourceId}
        databaseName={importExportDataBoundInfo.databaseName}
        schemaName={importExportDataBoundInfo.schemaName}
        tableName={importExportDataBoundInfo.tableName || ''}
        file={importFile.file}
        onSubmitted={(submittedTaskId) => {
          setTaskId(submittedTaskId);
          getTaskList();
        }}
      />
    );
  };

  const importMappingContent = renderImportMappingContent();

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
      footer={taskId ? logRenderFooter() : importMappingContent ? null : renderFooter()}
      maskClosable={false}
      onCancel={() => {
        setImportExportDataBoundInfo(null);
      }}
    >
      {taskId ? (
        <Log onTaskChange={handleTaskChange} taskId={taskId} />
      ) : importMappingContent ? (
        importMappingContent
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
