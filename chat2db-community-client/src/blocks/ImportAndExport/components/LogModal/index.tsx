import { memo, useEffect, useState } from 'react';
import { Modal } from '@chat2db/ui';
import Log from '@/blocks/ImportAndExport/components/Log';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import { Button, Dropdown, type MenuProps } from 'antd';
import { ImportExportTaskDetails } from '@/typings/importExport';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { ImportExportTaskStatus } from '@/constants/importExport';
import importExportServices, { artifactDownloadUrl } from '@/service/importExport';
import { Download, FolderOpen } from 'lucide-react';
import { getDownloadableTaskArtifacts, shouldShowLegacyPrimaryArtifact } from './artifactVisibility';

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
    const localArtifact = artifactId || taskDetails.artifactId;
    if (isDesktop && localArtifact) {
      jcefApi?.revealInExplorer(localArtifact);
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
