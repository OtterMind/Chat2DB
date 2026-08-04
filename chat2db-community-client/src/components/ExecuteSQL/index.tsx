import { memo, useRef, useState, useMemo, useEffect, forwardRef, useImperativeHandle } from 'react';
import MonacoEditor, { IExportRefFunction } from '@/components/MonacoEditor';
import i18n from '@/i18n';
import { formatSql } from '@/utils/sql/formatSql';
import sqlService from '@/service/sql';
import type { IDdlExecuteRequest, ITableEditExecuteRequest } from '@/service/dmlRequest';
import { useStyles } from './style';
import { v4 as uuid } from 'uuid';
import { IconButton } from '@chat2db/ui';
import { IDatabaseBaseInfo } from '@/typings/database';
import { cx } from 'antd-style';

interface IProps {
  className?: string;
  initSql?: string | null;
  initError?: string | null;
  databaseBaseInfo: IDatabaseBaseInfo;
  executeSqlApi?: 'executeUpdateDataSql'; // The two consumers require different SQL execution APIs.
  monacoEditorProps?: any;
  executeSuccessCallBack?: (res: any) => void;
}

export interface IExecuteSQLRef {
  getMonacoEditorRef: () => IExportRefFunction | null;
  resetArouseErrorTips: () => void;
}

const ExecuteSQL = forwardRef((props: IProps, ref) => {
  const {
    className,
    initSql = null,
    initError = null,
    databaseBaseInfo,
    executeSqlApi = 'executeDDL',
    executeSuccessCallBack,
    monacoEditorProps = {},
  } = props;
  const { dataSourceId, databaseType, databaseName, schemaName, tableName } = databaseBaseInfo;
  const { styles } = useStyles();
  const monacoEditorRef = useRef<IExportRefFunction>(null);
  const [executeLoading, setExecuteLoading] = useState<boolean>(false);
  const [executeSqlResult, setExecuteSqlResult] = useState<string | null>(null);
  const monacoEditorId = useMemo(() => uuid(), []);

  useEffect(() => {
    monacoEditorRef.current?.setValue(initSql || '', 'cover');
    setExecuteSqlResult(initError);
  }, []);

  const handleFormatSql = () => {
    const sql = monacoEditorRef.current?.getAllContent() || '';
    formatSql(sql, databaseType).then((res) => {
      monacoEditorRef.current?.setValue(res || '', 'cover');
    });
  };

  useImperativeHandle(ref, () => ({
    getMonacoEditorRef: () => monacoEditorRef.current,
    resetArouseErrorTips: () => {
      monacoEditorRef.current?.arouseErrorTips(null);
    },
  }));

  const executeSql = () => {
    if (dataSourceId == null) {
      return;
    }
    const baseRequest: ITableEditExecuteRequest = {
      sql: monacoEditorRef.current?.getAllContent() || '',
      dataSourceId,
      databaseName,
      schemaName,
    };
    const executeSQLParams: ITableEditExecuteRequest | IDdlExecuteRequest =
      executeSqlApi === 'executeUpdateDataSql' ? baseRequest : { ...baseRequest, tableName };
    setExecuteLoading(true);
    monacoEditorRef.current?.arouseErrorTips(null);
    setExecuteSqlResult(null);
    sqlService[executeSqlApi](executeSQLParams)
      .then((res) => {
        if (res.success) {
          executeSuccessCallBack?.(res);
          // staticMessage.success(i18n('common.text.successfulExecution'));
        } else {
          setExecuteSqlResult(res.message);
        }
      })
      .catch((error) => {
        monacoEditorRef.current?.arouseErrorTips(error.errorMessage || '');
      })
      .finally(() => {
        setExecuteLoading(false);
      });
  };

  return (
    <div className={cx(styles.monacoEditorModal, className)}>
      <div className={styles.monacoEditorContent}>
        <div className={styles.monacoEditorHeader}>
          <IconButton
            code="icon-play"
            spin={executeLoading}
            className={styles.executeButton}
            size="sm"
            disabled={executeLoading}
            title={i18n('common.button.execute')}
            onClick={executeSql}
          />
          <IconButton code="icon-geshihua" size="sm" title={i18n('common.button.format')} onClick={handleFormatSql} />
        </div>
        <MonacoEditor
          className={styles.monacoEditor}
          id={monacoEditorId}
          ref={monacoEditorRef}
          {...monacoEditorProps}
        />
      </div>
      {executeSqlResult && (
        <div className={styles.result}>
          <div className={styles.resultHeader}>{i18n('common.text.errorMessage')}</div>
          <div className={styles.resultContent}>
            <div className={styles.errorMessage}>{executeSqlResult}</div>
          </div>
        </div>
      )}
    </div>
  );
});

export default memo(ExecuteSQL);
