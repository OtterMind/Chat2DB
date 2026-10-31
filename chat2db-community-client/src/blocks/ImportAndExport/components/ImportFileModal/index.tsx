import { memo, useEffect, useRef, useState } from 'react';
import { Modal } from '@chat2db/ui';
import { Button } from 'antd';
import i18n from '@/i18n';
import ImportExportFile, { ImportExportFileRef } from '../ImportExportFile';
import { useImportExportStore } from '@/store/importExport';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices from '@/service/importExport';
import { ImportExportTaskType, ImportExportType } from '@/constants/importExport';
import Log from '@/blocks/ImportAndExport/components/Log';
import { ImportExportTaskDetails } from '@/typings/importExport';
import ImportWizard from '@/blocks/ImportAndExport/components/ImportWizard';
import TaskLogActions from '@/blocks/ImportAndExport/components/TaskLogActions';
import type { ImportWizardStepTitleKey } from '@/blocks/ImportAndExport/utils/importWizardSteps';
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
  const previousTaskDetailsRef = useRef<ImportExportTaskDetails>();
  const [taskId, setTaskId] = useState<number>();
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  /** The wizard reports its step so the dialog title can name it; null while it is not running. */
  const [wizardStepTitleKey, setWizardStepTitleKey] = useState<ImportWizardStepTitleKey | null>(null);

  const { importExportDataBoundInfo, importExportPrefill, setImportExportDataBoundInfo, getTaskList } =
    useImportExportStore((state) => {
      return {
        importExportDataBoundInfo: state.importExportDataBoundInfo,
        importExportPrefill: state.importExportPrefill,
        setImportExportDataBoundInfo: state.setImportExportDataBoundInfo,
        getTaskList: state.getTaskList,
      };
    });

  const isImport = importExportDataBoundInfo?.type === ImportExportType.IMPORT;

  useEffect(() => {
    if (!importExportDataBoundInfo) {
      setTaskId(undefined);
      setTaskDetails(undefined);
      setWizardStepTitleKey(null);
      previousTaskDetailsRef.current = undefined;
    }
  }, [importExportDataBoundInfo]);

  /** Exports keep the single form; imports use the file-format-specific wizard. */
  const handleRunExport = async () => {
    const params = importExportFileRef.current?.getValues();
    // The export form always builds a table export; anything else belongs to the import wizard.
    if (!params || params.taskType !== ImportExportTaskType.TABLE_DATA_EXPORT) return;
    const response = await importExportServices.submitExport(params);
    setTaskId(response.taskId);
    getTaskList();
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
            <Button type="primary" disabled={!isReady} onClick={handleRunExport}>
              {i18n('workspace.importExport.exportAction')}
            </Button>
          </>
        }
      />
    );
  };

  const logRenderFooter = () => (
    <ModalFooterButton
      footerRight={
        <>
          <TaskLogActions
            taskDetails={taskDetails}
            onResumeStart={() => setImportExportDataBoundInfo(null)}
          />
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

  const handleSubmitted = (submittedTaskId: number) => {
    setTaskId(submittedTaskId);
    getTaskList();
  };

  // The import wizard has no step bar, so the dialog title names the step it is showing.
  const dialogTitle = isImport
    ? !taskId && wizardStepTitleKey
      ? `${i18n('workspace.menu.importData')} · ${i18n(wizardStepTitleKey)}`
      : i18n('workspace.menu.importData')
    : i18n('workspace.menu.exportData');

  return (
    <Modal
      open={!!importExportDataBoundInfo}
      okText={i18n('common.button.start')}
      cancelText={i18n('common.button.cancel')}
      title={dialogTitle}
      headerIconCode={isImport ? 'icon-upload' : 'icon-download'}
      width={isImport ? 1020 : undefined}
      centered
      destroyOnClose
      footer={taskId ? logRenderFooter() : isImport ? null : renderFooter()}
      maskClosable={false}
      onCancel={() => {
        setImportExportDataBoundInfo(null);
      }}
    >
      {taskId ? (
        <Log onTaskChange={handleTaskChange} taskId={taskId} />
      ) : isImport ? (
        <ImportWizard
          restoredSpec={importExportPrefill}
          onSubmitted={handleSubmitted}
          onStepTitleChange={setWizardStepTitleKey}
        />
      ) : (
        <ImportExportFile ref={importExportFileRef} restoredSpec={importExportPrefill} setIsReady={setIsReady} />
      )}
    </Modal>
  );
});
