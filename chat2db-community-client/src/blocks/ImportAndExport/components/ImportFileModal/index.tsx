import { memo, useEffect, useRef, useState } from 'react';
import { Modal, IconfontSvg } from '@chat2db/ui';
import { Button } from 'antd';
import i18n from '@/i18n';
import ImportExportFile, { ImportExportFileRef } from '../ImportExportFile';
import { useImportExportStore } from '@/store/importExport';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices from '@/service/importExport';
import { ImportExportType } from '@/constants/importExport';
import Log from '@/blocks/ImportAndExport/components/Log';
import { ImportExportTaskDetails } from '@/typings/importExport';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';
import { openHostArtifact } from '@/utils/hostFileTransfer';

interface IProps {
  className?: string;
}

export default memo<IProps>((_props) => {
  const [isReady, setIsReady] = useState(false);
  const importExportFileRef = useRef<ImportExportFileRef>(null);
  const [taskId, setTaskId] = useState<number>();
  const [taskDetails, setTaskDetails] = useState<ImportExportTaskDetails>();

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
    }
  }, [importExportDataBoundInfo]);

  const handleRunSQl = () => {
    const api =
      importExportDataBoundInfo?.type === ImportExportType.IMPORT
        ? importExportServices.importOtherFile
        : importExportServices.exportOtherFile;
    const params = importExportFileRef.current?.getValues() || {};
    api(params).then((res) => {
      setTaskId(res);
      getTaskList({ visible: true });
    });
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
    void openHostArtifact(taskDetails?.downloadUrl, {
      desktop: isDesktop,
      revealInExplorer: jcefApi.revealInExplorer,
    }).catch((error) => {
      console.error('Failed to open exported file:', error);
    });
  };

  const logRenderFooter = () => (
    <ModalFooterButton
      footerLeft={
        <>
          {importExportDataBoundInfo?.type === ImportExportType.EXPORT && taskDetails?.downloadUrl && (
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
              setImportExportDataBoundInfo(null);
            }}
          >
            {i18n('common.button.close')}
          </Button>
        </>
      }
    />
  );

  const finish = (_taskDetails: ImportExportTaskDetails) => {
    setTaskDetails(_taskDetails);
  };

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
      footer={taskId ? logRenderFooter() : renderFooter()}
      maskClosable={false}
      onCancel={() => {
        setImportExportDataBoundInfo(null);
      }}
    >
      {taskId ? (
        <Log finish={finish} taskId={taskId} />
      ) : (
        <ImportExportFile ref={importExportFileRef} setIsReady={setIsReady} />
      )}
    </Modal>
  );
});
