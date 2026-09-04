import { memo, useEffect, useState } from 'react';
import { Modal } from '@chat2db/ui';
import Log from '@/blocks/ImportAndExport/components/Log';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import { Button } from 'antd';
import { ImportExportTaskDetails } from '@/typings/importExport';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { ImportExportTaskStatus } from '@/constants/importExport';
import { Download, FolderOpen } from 'lucide-react';
import importExportServices from '@/service/importExport';
import { useGlobalStore } from '@/store/global';

interface IProps {
  className?: string;
}

const LogModal = (_props: IProps) => {
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const [cancelSubmitting, setCancelSubmitting] = useState(false);
  const openUnifiedConfirmationModal = useGlobalStore((state) => state.openUnifiedConfirmationModal);
  const { logModalTaskId, openLogModal, getTaskList } = useImportExportStore((state) => {
    return {
      logModalTaskId: state.logModalTaskId,
      openLogModal: state.openLogModal,
      getTaskList: state.getTaskList,
    };
  });

  useEffect(() => {
    setTaskDetails(undefined);
    setCancelSubmitting(false);
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
    if (!taskDetails || cancelSubmitting) return;
    openUnifiedConfirmationModal({
      title: i18n('workspace.task.cancel.confirmTitle'),
      content: i18n('workspace.task.cancel.confirm', taskDetails.name),
      onOk: () => {
        setCancelSubmitting(true);
        return importExportServices
          .cancelTask({ taskId: taskDetails.id })
          .then(() => getTaskList())
          .finally(() => setCancelSubmitting(false));
      },
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
          {taskDetails &&
            [ImportExportTaskStatus.PENDING, ImportExportTaskStatus.RUNNING].includes(taskDetails.status) && (
              <Button danger loading={cancelSubmitting} onClick={handleCancelTask}>
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
