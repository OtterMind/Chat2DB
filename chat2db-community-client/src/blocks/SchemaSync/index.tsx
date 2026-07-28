import {
  memo,
  forwardRef,
  useImperativeHandle,
  ForwardedRef,
  useState,
  useMemo,
  useRef,
  useEffect,
} from 'react';
// import i18n from '@/i18n';
import { useStyles } from './style';
import SelectDatabase, { type IDiffData } from './components/SelectDatabase';
import { Button } from 'antd';
import schemaSyncService from '@/service/schemaSync';
import MonacoEditor, { MonacoEditorRef } from '@/components/SQLEditor/editor/MonacoEditor';
import { v4 as uuidv4 } from 'uuid';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import useSqlExecutor from '@/hooks/useSqlExecutor';
import Abstract from '@/components/Abstract';
import i18n from '@/i18n';
import { type ISelectDatabase } from '@/hooks/useSelectDatabase';

interface IProps {
  className?: string;
  initSourceData: ISelectDatabase;
  onClose?: () => void;
}

export interface SchemaSyncRef {
  getX: () => number;
}

const SchemaSync = forwardRef((props: IProps, ref: ForwardedRef<SchemaSyncRef>) => {
  const { className, onClose, initSourceData } = props;
  const { styles, cx } = useStyles();
  const [diffData, setDiffData] = useState<IDiffData>();
  const [step, setStep] = useState<number>(0);
  const monacoEditorRef = useRef<MonacoEditorRef>(null);
  const [diffSql, setDiffSql] = useState<string>('');
  const [buttonLoading, setButtonLoading] = useState<boolean>(false);
  const consoleId = useMemo(() => uuidv4(), []);
  const { executeSQL, canExecuteSQL } = useSqlExecutor();
  const [executeResult, setExecuteResult] = useState<any>();

  useImperativeHandle(ref, () => ({
    getX: () => {
      return 1;
    },
  }));

  const handleSelectDatabaseChange = (values: IDiffData) => {
    setDiffData(values);
  };

  useEffect(() => {
    if (step === 1) {
      monacoEditorRef.current?.setValue(diffSql, 'cover');
    }
  }, [step]);

  const handleStartDiff = () => {
    setButtonLoading(true);
    schemaSyncService
      .getSchemaSyncSql(diffData)
      .then((res) => {
        setDiffSql(res);
        setStep(1);
      })
      .finally(() => {
        setButtonLoading(false);
      });
  };

  const handleSqlExecutor = () => {
    if (!canExecuteSQL()) {
      return;
    }
    setButtonLoading(true);
    executeSQL({
      sql: monacoEditorRef.current?.getValue() || '',
      dataSourceId: diffData?.target?.dataSourceId,
      databaseName: diffData?.target?.databaseName,
      schemaName: diffData?.target?.schemaName,
    })
      .then((res) => {
        setExecuteResult(res);
        setStep(2);
      })
      .finally(() => {
        setButtonLoading(false);
      });
  };

  const handleClose = () => {
    onClose && onClose();
  };

  const renderFooterRight = () => {
    if (step === 0) {
      return (
        <Button type="primary" disabled={!diffData} onClick={handleStartDiff} loading={buttonLoading}>
          {i18n('workspace.syncStructure.startDiff')}
        </Button>
      );
    }
    if (step === 1) {
      return (
        <Button type="primary" disabled={!diffData} onClick={handleSqlExecutor} loading={buttonLoading}>
          {i18n('workspace.syncStructure.startExecute')}
        </Button>
      );
    }
    if (step === 2) {
      return <Button onClick={handleClose}>{i18n('common.button.close')}</Button>;
    }
  };

  return (
    <div className={cx(className, styles.container)}>
      <div className={styles.body}>
        <SelectDatabase
          className={cx({ [styles.hiddenStep]: step !== 0 })}
          initSourceData={initSourceData}
          onChanges={handleSelectDatabaseChange}
        />
        <div className={cx({ [styles.hiddenStep]: step !== 1 }, styles.monacoEditor)}>
          <MonacoEditor ref={monacoEditorRef} id={consoleId} />
        </div>
        {step === 2 && executeResult && (
          <div className={styles.abstractBox}>
            <Abstract
              customOptions={{
                showLeftBorder: true,
              }}
              data={executeResult}
            />
          </div>
        )}
      </div>
      <ModalFooterButton
        footerLeft={
          <Button
            disabled={step === 0}
            onClick={() => {
              setStep(step - 1);
            }}
          >
            {i18n('common.button.prev')}
          </Button>
        }
        footerRight={renderFooterRight()}
      />
    </div>
  );
});

export default memo(SchemaSync);
