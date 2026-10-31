import { memo, useState, useRef, useEffect } from 'react';
import { Modal, staticMessage } from '@chat2db/ui';
import { Button } from 'antd';
import i18n from '@/i18n';
import RunSql, { RunSqlRef } from '../RunSql';
import { useImportExportStore } from '@/store/importExport';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices from '@/service/importExport';
import sqlService from '@/service/sql';
import Log from '@/blocks/ImportAndExport/components/Log';
import { prepareWebImportParams } from '../ImportFileModal/submission';
import { isDesktop } from '@/utils/env';

interface IProps {
  className?: string;
}

export default memo<IProps>((_props) => {
  const [isReady, setIsReady] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const runSqlRef = useRef<RunSqlRef>(null);
  const [taskId, setTaskId] = useState<number>();

  const { runSqlBoundInfo, setRunSqlBoundInfo, getTaskList } = useImportExportStore((state) => {
    return {
      runSqlBoundInfo: state.runSqlBoundInfo,
      setRunSqlBoundInfo: state.setRunSqlBoundInfo,
      getTaskList: state.getTaskList,
    };
  });

  useEffect(() => {
    if (!runSqlBoundInfo) {
      setTaskId(undefined);
    }
  }, [runSqlBoundInfo]);

  const handleRunSQl = async () => {
    const params = runSqlRef.current?.getValues();
    if (!params || submitting) return;
    const selectedFile = runSqlRef.current?.getSelectedFile();
    if (!isDesktop && !selectedFile && !params.sourceFile) {
      staticMessage.error(i18n('workspace.importExport.wizard.selectFileFirst'));
      return;
    }
    setSubmitting(true);
    try {
      // The browser has no local path to submit: upload the picked file first and send its id,
      // exactly like the import wizard's SQL script path. The desktop bridge still sends the path.
      const importParams = selectedFile
        ? await prepareWebImportParams(params, selectedFile, sqlService.uploadImportFile)
        : params;
      const res = await importExportServices.submitImport(importParams);
      setTaskId(res.taskId);
      getTaskList();
    } catch (error) {
      const failure = error as { errorMessage?: string; message?: string };
      staticMessage.error(failure?.errorMessage || failure?.message || i18n('common.text.failure'));
    } finally {
      setSubmitting(false);
    }
  };

  const renderFooter = () => {
    return (
      <ModalFooterButton
        footerRight={
          <>
            <Button
              onClick={() => {
                setRunSqlBoundInfo(null);
              }}
            >
              {i18n('common.button.cancel')}
            </Button>
            <Button type="primary" loading={submitting} disabled={!isReady || submitting} onClick={handleRunSQl}>
              {i18n('common.button.start')}
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
          <Button
            onClick={() => {
              setRunSqlBoundInfo(null);
            }}
          >
            {i18n('common.button.close')}
          </Button>
        </>
      }
    />
  );

  return (
    <Modal
      open={!!runSqlBoundInfo}
      okText={i18n('common.button.start')}
      cancelText={i18n('common.button.cancel')}
      title={i18n('workspace.menu.runSqlFile')}
      headerIconCode="icon-run-sql"
      headerBorder
      destroyOnClose
      maskClosable={false}
      footer={taskId ? logRenderFooter() : renderFooter()}
      onCancel={() => {
        setRunSqlBoundInfo(null);
      }}
    >
      {taskId ? <Log taskId={taskId} /> : <RunSql ref={runSqlRef} setIsReady={setIsReady} />}
    </Modal>
  );
});
