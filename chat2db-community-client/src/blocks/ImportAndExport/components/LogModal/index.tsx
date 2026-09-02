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
import importExportServices, { artifactDownloadUrl } from '@/service/importExport';
import { Download, FolderOpen } from 'lucide-react';

interface IProps {
  className?: string;
}

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

  const handleOpenFile = (artifactId?: string) => {
    if (!taskDetails) return;
    if (isDesktop && (artifactId || taskDetails.artifactId)) {
      jcefApi?.revealInExplorer(artifactId || taskDetails.artifactId);
      return;
    }
    window.open(artifactDownloadUrl({ taskId: taskDetails.id, artifactId }), '_blank');
  };

  const handleResume = () => {
    if (!taskDetails) return;
    importExportServices.resumeTask({ taskId: taskDetails.id }).then(() => {
      openLogModal(taskDetails.id);
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
          {taskDetails?.status === ImportExportTaskStatus.PENDING && taskDetails?.stage === 'RESUMING' && (
            <Button type="primary" onClick={handleResume}>
              {i18n('workspace.task.action.resume')}
            </Button>
          )}
          {taskDetails?.status === ImportExportTaskStatus.SUCCESS &&
            (taskDetails.artifacts?.length
              ? taskDetails.artifacts.map((artifact) => (
                  <Button
                    key={artifact.artifactId}
                    type={artifact.role === 'OUTPUT' ? 'primary' : 'default'}
                    icon={isDesktop ? <FolderOpen aria-hidden size={15} /> : <Download aria-hidden size={15} />}
                    onClick={() => handleOpenFile(artifact.artifactId)}
                  >
                    {artifact.artifactId.split(/[\\/]/).pop()}
                  </Button>
                ))
              : taskDetails.artifactId && (
                  <Button
                    type="primary"
                    icon={isDesktop ? <FolderOpen aria-hidden size={15} /> : <Download aria-hidden size={15} />}
                    onClick={() => handleOpenFile()}
                  >
                    {i18n('workspace.text.openFile')}
                  </Button>
                ))}
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
