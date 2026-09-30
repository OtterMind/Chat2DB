import { memo, useEffect, useRef, useState } from 'react';
import { Modal } from '@chat2db/ui';
import { Button } from 'antd';
import i18n from '@/i18n';
import ImportExportFile, { ImportExportFileRef } from '../ImportExportFile';
import MultiTableImportWizard from '../MultiTableImportWizard';
import { useImportExportStore } from '@/store/importExport';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices, { type ExportTaskParams, type ImportTaskParams } from '@/service/importExport';
import { ImportExportFileType, ImportExportTaskStatus, ImportExportType } from '@/constants/importExport';
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
  const submittedImportTargetsRef = useRef<Array<NonNullable<ImportExportTaskDetails['target']>>>([]);
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
      submittedImportTargetsRef.current = [];
    }
  }, [importExportDataBoundInfo]);

  const handleRunSQl = () => {
    if (submitting) return;
    const params = importExportFileRef.current?.getValues();
    if (!params) return;
    setSubmitting(true);
    const isImportRequest = params.taskType === 'DATA_FILE_IMPORT' || params.taskType === 'SQL_FILE_IMPORT';
    submittedImportTargetsRef.current = isImportRequest
      ? ((params as ImportTaskParams).tableSources || []).map((source) => ({
          dataSourceId: (params as ImportTaskParams).dataSourceId,
          databaseName: source.databaseName || (params as ImportTaskParams).databaseName,
          schemaName: source.schemaName,
          tableName: source.tableName,
        }))
      : [];
    const request = isImportRequest
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
            taskDetails?.status === ImportExportTaskStatus.SUCCESS &&
            taskDetails.artifactId && (
              <Button
                type="primary"
                icon={isDesktop ? undefined : undefined}
                onClick={handleOpenFile}
              >
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
    const becameSuccessful =
      _taskDetails.status === ImportExportTaskStatus.SUCCESS &&
      (!previous || previous.id === _taskDetails.id) &&
      previous?.status !== ImportExportTaskStatus.SUCCESS;
    if (becameSuccessful && submittedImportTargetsRef.current.length) {
      submittedImportTargetsRef.current.forEach((target) => {
        window.dispatchEvent(new CustomEvent(IMPORT_TARGET_TABLE_REFRESH_EVENT, { detail: target }));
      });
      getTaskList();
      return;
    }
    if (shouldRefreshImportTargetTable(previous, _taskDetails)) {
      window.dispatchEvent(new CustomEvent(IMPORT_TARGET_TABLE_REFRESH_EVENT, { detail: _taskDetails.target }));
      getTaskList();
    }
  };

  // The wizard can only build a submission for the scopes it supports; anything else would render
  // a wizard whose start button can never produce params.
  const isMultiTableImport =
    importExportDataBoundInfo?.type === ImportExportType.IMPORT &&
    (importExportDataBoundInfo.targetScope === 'SCHEMA' ||
      importExportDataBoundInfo.targetScope === 'DATABASE') &&
    importExportDataBoundInfo.fileType !== ImportExportFileType.SQL;

  const modalTitle = (() => {
    if (importExportDataBoundInfo?.type === ImportExportType.IMPORT) {
      if (isMultiTableImport) {
        return i18n('workspace.menu.importMultipleTables');
      }
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
    >
      {taskId ? (
        <Log onTaskChange={handleTaskChange} taskId={taskId} />
      ) : isMultiTableImport && importExportDataBoundInfo ? (
        <MultiTableImportWizard
          ref={importExportFileRef}
          boundInfo={importExportDataBoundInfo}
          setIsReady={setIsReady}
        />
      ) : (
        <ImportExportFile ref={importExportFileRef} setIsReady={setIsReady} />
      )}
    </Modal>
  );
});
