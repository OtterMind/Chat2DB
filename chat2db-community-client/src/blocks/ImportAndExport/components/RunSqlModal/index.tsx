import { memo, useState, useRef, useEffect } from 'react';
import { Modal } from '@chat2db/ui';
import { Button } from 'antd';
import i18n from '@/i18n';
import RunSql, { RunSqlRef } from '../RunSql';
import { useImportExportStore } from '@/store/importExport';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import importExportServices from '@/service/importExport';
import Log from '@/blocks/ImportAndExport/components/Log';

interface IProps {
  className?: string;
}

export default memo<IProps>((_props) => {
  const [isReady, setIsReady] = useState(false);
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

  const handleRunSQl = () => {
    const params = runSqlRef.current?.getValues();
    if (!params) return;
    importExportServices.submitImport(params).then((res) => {
      setTaskId(res.taskId);
      getTaskList();
    });
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
            <Button type="primary" disabled={!isReady} onClick={handleRunSQl}>
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
