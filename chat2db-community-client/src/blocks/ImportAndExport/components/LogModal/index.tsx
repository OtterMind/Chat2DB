import { memo, useEffect, useState } from 'react';
import { Modal } from '@chat2db/ui';
import Log from '@/blocks/ImportAndExport/components/Log';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import { Button } from 'antd';
import { ImportExportTaskDetails } from '@/typings/importExport';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { ACTIVE_TASK_STATUSES, ImportExportTaskStatus } from '@/constants/importExport';
import importExportServices from '@/service/importExport';
import { CircleStop, Download, FolderOpen } from 'lucide-react';

interface IProps {
  className?: string;
}

const LogModal = (_props: IProps) => {
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const { logModalTaskId, openLogModal, getTaskList } = useImportExportStore((state) => {
    return {
      logModalTaskId: state.logModalTaskId,
      openLogModal: state.openLogModal,
      getTaskList: state.getTaskList,
    };
  });
  const openUnifiedConfirmationModal = useGlobalStore((state) => state.openUnifiedConfirmationModal);

  useEffect(() => {
    setTaskDetails(undefined);
  }, [logModalTaskId]);

  const handleOpenFile = () => {
    if (!taskDetails) return;
    if (isDesktop && taskDetails.artifactId) {
      jcefApi?.revealInExplorer(taskDetails.artifactId);
      return;
    }
    window.open(`/api/tasks/artifact?taskId=${taskDetails.id}`, '_blank');
  };

  const handleCancelTask = () => {
    if (!taskDetails) return;
    openUnifiedConfirmationModal({
      title: i18n('workspace.task.cancel.confirmTitle'),
      content: i18n('workspace.task.cancel.confirm', taskDetails.name),
      onOk: () => importExportServices.cancelTask({ taskId: taskDetails.id }).then(() => getTaskList()),
    });
  };

  const renderFooter = (
    <ModalFooterButton
      footerRight={
        <>
          <Button
            onClick={() => {
              openLogModal(null);
            }}
          >
            {i18n('common.button.close')}
          </Button>
          {taskDetails && ACTIVE_TASK_STATUSES.includes(taskDetails.status) && (
            <Button icon={<CircleStop aria-hidden size={15} />} onClick={handleCancelTask}>
              {i18n('common.button.cancel')}
            </Button>
          )}
          {taskDetails?.status === ImportExportTaskStatus.SUCCESS && taskDetails.artifactId && (
            <Button
              type="primary"
              icon={isDesktop ? <FolderOpen aria-hidden size={15} /> : <Download aria-hidden size={15} />}
              onClick={handleOpenFile}
            >
              {i18n('workspace.text.openFile')}
            </Button>
          )}
        </>
      }
    />
  );

  const handleTaskChange = (details: ImportExportTaskDetails) => {
    setTaskDetails(details);
  };

  return (
    <Modal
      className={_props.className}
      open={logModalTaskId !== null}
      footer={renderFooter}
      title={i18n('workspace.title.logDetail')}
      headerIconCode="icon-formatting"
      headerBorder
      width={780}
      maxHeight="calc(100vh - 48px)"
      padding={0}
      centered
      destroyOnClose
      maskClosable={false}
      onCancel={() => {
        openLogModal(null);
      }}
    >
      {logModalTaskId && <Log taskId={logModalTaskId} onTaskChange={handleTaskChange} />}
    </Modal>
  );
};

export default memo(LogModal);
