import { memo, useEffect, useState } from 'react';
import { Modal } from '@chat2db/ui';
import Log from '@/blocks/ImportAndExport/components/Log';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import { Button } from 'antd';
import { ImportExportTaskDetails } from '@/typings/importExport';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import TaskLogActions from '@/blocks/ImportAndExport/components/TaskLogActions';

interface IProps {
  className?: string;
}

/** Log dialog of the task center: the same events and actions the progress dialog of a task shows. */
const LogModal = (_props: IProps) => {
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const { logModalTaskId, openLogModal } = useImportExportStore((state) => {
    return {
      logModalTaskId: state.logModalTaskId,
      openLogModal: state.openLogModal,
    };
  });

  useEffect(() => {
    setTaskDetails(undefined);
  }, [logModalTaskId]);

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
          <TaskLogActions taskDetails={taskDetails} />
        </>
      }
    />
  );

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
      {logModalTaskId && <Log taskId={logModalTaskId} onTaskChange={setTaskDetails} />}
    </Modal>
  );
};

export default memo(LogModal);
