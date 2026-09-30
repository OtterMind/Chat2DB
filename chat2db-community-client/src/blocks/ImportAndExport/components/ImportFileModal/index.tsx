import { memo, useEffect, useRef, useState } from 'react';
import { Modal } from '@chat2db/ui';
import { Button, Dropdown, type MenuProps } from 'antd';
import i18n from '@/i18n';
import ImportExportFile, { ImportExportFileRef } from '../ImportExportFile';
import MultiTableImportWizard from '../MultiTableImportWizard';
import { useImportExportStore } from '@/store/importExport';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices, {
  artifactDownloadUrl,
  type ExportTaskParams,
  type ImportTaskParams,
} from '@/service/importExport';
import { ImportExportFileType, ImportExportTaskStatus, ImportExportType } from '@/constants/importExport';
import Log from '@/blocks/ImportAndExport/components/Log';
import { ImportExportTaskDetails } from '@/typings/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import {
  IMPORT_TARGET_TABLE_REFRESH_EVENT,
  shouldRefreshImportTargetTable,
} from '@/store/importExport/taskCenterUtils';
import {
  createClientSubmissionId,
  getServerStagedImportFileIds,
  hasServerStagedImportFiles,
  importSubmissionIdentityAfterFailure,
  isUnknownSubmissionResponse,
  prepareImportSubmission,
  type ImportSubmissionIdentity,
} from './submission';
import { Download, FolderOpen } from 'lucide-react';
import {
  getDownloadableTaskArtifacts,
  shouldShowLegacyPrimaryArtifact,
} from '../LogModal/artifactVisibility';

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
  const submissionGenerationRef = useRef(0);
  const importSubmissionIdentityRef = useRef<ImportSubmissionIdentity>();
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
      importSubmissionIdentityRef.current = undefined;
    }
  }, [importExportDataBoundInfo]);

  const handleRunSQl = () => {
    if (submitting) return;
    const importing = importExportDataBoundInfo?.type === ImportExportType.IMPORT;
    const proposedClientSubmissionId = importing
      ? importSubmissionIdentityRef.current?.clientSubmissionId || createClientSubmissionId()
      : undefined;
    const params = importExportFileRef.current?.getValues(proposedClientSubmissionId);
    if (!params) return;
    setSubmitting(true);
    const isImportRequest = params.taskType === 'DATA_FILE_IMPORT' || params.taskType === 'SQL_FILE_IMPORT';
    const preparedImport = isImportRequest
      ? prepareImportSubmission(params as ImportTaskParams, importSubmissionIdentityRef.current)
      : undefined;
    const importParams = preparedImport?.params;
    if (preparedImport) importSubmissionIdentityRef.current = preparedImport.identity;
    const hasServerStagedFiles = importParams ? hasServerStagedImportFiles(importParams) : false;
    const submittedStagedFileIds = importParams ? getServerStagedImportFileIds(importParams) : [];
    const submissionGeneration = ++submissionGenerationRef.current;
    if (importParams) {
      submittedImportTargetsRef.current = (importParams.tableSources || []).map((source) => ({
        dataSourceId: importParams.dataSourceId,
        databaseName: source.databaseName || importParams.databaseName,
        schemaName: source.schemaName,
        tableName: source.tableName,
      }));
    } else {
      submittedImportTargetsRef.current = [];
    }
    const request = importParams
      ? importExportServices.submitImport(importParams)
      : importExportServices.submitExport(params as ExportTaskParams);
    request
      .then((res) => {
        void getTaskList();
        if (submissionGeneration !== submissionGenerationRef.current) return;
        importExportFileRef.current?.markStagedFilesSubmitted(submittedStagedFileIds);
        setTaskId(res.taskId);
      })
      .catch((error) => {
        if (submissionGeneration !== submissionGenerationRef.current) return;
        const unknownResponse = isUnknownSubmissionResponse(error);
        if (isImportRequest) {
          importSubmissionIdentityRef.current = importSubmissionIdentityAfterFailure(
            importSubmissionIdentityRef.current,
            error,
          );
        }
        if (isImportRequest && hasServerStagedFiles && !unknownResponse) {
          importExportFileRef.current?.invalidateStagedFiles(
            i18n('workspace.importExport.multiTable.submissionFilesExpired'),
          );
          submittedImportTargetsRef.current = [];
          setIsReady(false);
        }
      })
      .finally(() => {
        if (submissionGeneration === submissionGenerationRef.current) setSubmitting(false);
      });
  };

  const closeModal = () => {
    submissionGenerationRef.current += 1;
    setSubmitting(false);
    importSubmissionIdentityRef.current = undefined;
    if (!taskId) importExportFileRef.current?.releaseStagedFiles();
    setImportExportDataBoundInfo(null);
  };

  const renderFooter = () => {
    return (
      <ModalFooterButton
        footerRight={
          <>
            <Button disabled={submitting} onClick={closeModal}>
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

  const handleOpenFile = (artifactId?: string) => {
    if (!taskDetails) return;
    const localArtifact = artifactId || taskDetails.artifactId;
    if (isDesktop) {
      if (localArtifact) jcefApi.revealInExplorer(localArtifact);
      return;
    }
    window.open(artifactDownloadUrl({ taskId: taskDetails.id, artifactId }), '_blank');
  };

  const downloadableArtifacts = getDownloadableTaskArtifacts(taskDetails);
  const artifactMenuItems: MenuProps['items'] = downloadableArtifacts.map((artifact) => {
    const fileName = artifact.artifactId.split(/[\\/]/).pop() || artifact.artifactId;
    return {
      // artifactId is unique per task (task_artifact primary key), so the click target stays
      // correct when a refresh reorders the artifact list.
      key: artifact.artifactId,
      icon: isDesktop ? <FolderOpen aria-hidden size={15} /> : <Download aria-hidden size={15} />,
      label: (
        <span
          title={fileName}
          style={{ display: 'block', maxWidth: 'min(70vw, 420px)', overflow: 'hidden', textOverflow: 'ellipsis' }}
        >
          {fileName}
        </span>
      ),
    };
  });

  const logRenderFooter = () => (
    <ModalFooterButton
      footerLeft={
        <>
          {downloadableArtifacts.length ? (
            <Dropdown
              trigger={['click']}
              overlayStyle={{ maxWidth: 'calc(100vw - 32px)', maxHeight: 'min(60vh, 360px)', overflowY: 'auto' }}
              menu={{
                items: artifactMenuItems,
                onClick: ({ key }) => handleOpenFile(String(key)),
              }}
            >
              <Button
                type={downloadableArtifacts.some(({ role }) => role === 'OUTPUT') ? 'primary' : 'default'}
                icon={isDesktop ? <FolderOpen aria-hidden size={15} /> : <Download aria-hidden size={15} />}
              >
                {i18n('workspace.importExport.taskArtifacts')} ({downloadableArtifacts.length})
              </Button>
            </Dropdown>
          ) : (
            shouldShowLegacyPrimaryArtifact(taskDetails) && (
              <Button
                type="primary"
                icon={isDesktop ? <FolderOpen aria-hidden size={15} /> : <Download aria-hidden size={15} />}
                onClick={() => handleOpenFile()}
              >
                {i18n('workspace.text.openFile')}
              </Button>
            )
          )}
        </>
      }
      footerRight={
        <>
          <Button
            onClick={closeModal}
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
      void getTaskList();
      return;
    }
    if (shouldRefreshImportTargetTable(previous, _taskDetails)) {
      window.dispatchEvent(new CustomEvent(IMPORT_TARGET_TABLE_REFRESH_EVENT, { detail: _taskDetails.target }));
      void getTaskList();
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
      closable={!submitting}
      onCancel={() => {
        if (!submitting) closeModal();
      }}
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
