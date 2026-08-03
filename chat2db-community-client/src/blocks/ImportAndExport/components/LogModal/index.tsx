import { memo, useState, forwardRef, ForwardedRef, useImperativeHandle, useRef } from 'react';
import { Modal, IconfontSvg } from '@chat2db/ui';
import Log from '@/blocks/ImportAndExport/components/Log';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import { Button } from 'antd';
import { ImportExportTaskDetails } from '@/typings/importExport';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { openHostArtifact } from '@/utils/hostFileTransfer';

interface IProps {
  className?: string;
}

export interface LogModalRef {}

const LogModal = forwardRef((_props: IProps, ref: ForwardedRef<LogModalRef>) => {
  const logRef = useRef(null);
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();
  const { logModalTaskId, openLogModal } = useImportExportStore((state) => {
    return {
      logModalTaskId: state.logModalTaskId,
      openLogModal: state.openLogModal,
    };
  });

  const handleOpenFile = () => {
    void openHostArtifact(taskDetails?.downloadUrl, {
      desktop: isDesktop,
      revealInExplorer: jcefApi.revealInExplorer,
    }).catch((error) => {
      console.error('Failed to open exported file:', error);
    });
  };

  const renderFooter = (
    <ModalFooterButton
      footerLeft={
        <>
          {taskDetails?.downloadUrl && (
            <Button
              icon={<IconfontSvg code="icon-folder" />}
              disabled={!taskDetails?.downloadUrl}
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
              openLogModal(null);
            }}
          >
            {i18n('common.button.close')}
          </Button>
          {/* <Button type="primary">Stop</Button> */}
        </>
      }
    />
  );

  useImperativeHandle(ref, () => ({
    // openLogModal,
  }));

  const finish = (details: ImportExportTaskDetails) => {
    setTaskDetails(details);
  };

  return (
    <Modal
      open={logModalTaskId !== null}
      footer={renderFooter}
      title={i18n('workspace.title.logDetail')}
      headerIconCode="icon-formatting"
      headerBorder
      destroyOnClose
      maskClosable={false}
      onCancel={() => {
        openLogModal(null);
      }}
    >
      {logModalTaskId && <Log ref={logRef} taskId={logModalTaskId} finish={finish} />}
    </Modal>
  );
});

export default memo(LogModal);
