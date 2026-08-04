import { memo, forwardRef, useImperativeHandle, ForwardedRef, useState, useRef } from 'react';
// import { useStyles } from './style';
import { Modal } from 'antd';
import i18n from '@/i18n';
import ExecuteSQL, { IExecuteSQLRef } from '@/components/ExecuteSQL';
import executeSql from '@/service/executeSql';
import useSqlExecutor from '@/hooks/useSqlExecutor';
import { getRequestErrorMessage, resolveUpdateExecuteParams } from './updateSql';

interface IProps {
  className?: string;
  onExecuteSuccess?: () => void;
  onExecuteError?: (errorMessage: string) => void;
}

export interface SQLPreviewExecuteRef {
  handleViewSQL: (params: any) => any;
  handleExecuteSql: (params: any) => any;
}

const SQLPreviewExecute = forwardRef((props: IProps, ref: ForwardedRef<SQLPreviewExecuteRef>) => {
  const { onExecuteSuccess, onExecuteError } = props;
  // const { styles, cx } = useStyles();
  const [viewUpdateDataSqlModal, setViewUpdateDataSqlModal] = useState<boolean>(false);
  const [updateDataSql, setUpdateDataSql] = useState<string | null>(null);
  const [executeSqlParams, setExecuteSqlParams] = useState<any>(null);
  const executeSQLRef = useRef<IExecuteSQLRef>();
  const executePendingRef = useRef(false);
  const { executeSQL, canExecuteSQL } = useSqlExecutor();

  const getUpdateDataSql = ({ operations, resultData }) => {
    if (!operations.length) {
      return Promise.resolve({
        ...(resultData.executeSqlParams || {}),
        sql: '',
      });
    }
    return resolveUpdateExecuteParams({
      operations,
      resultData,
      getUpdateDataSql: executeSql.getUpdateDataSql,
    });
  };

  const handleViewSQL = ({ operations, resultData }) => {
    setViewUpdateDataSqlModal(true);
    setExecuteSqlParams(resultData.executeSqlParams);
    getUpdateDataSql({ operations, resultData }).then(({ sql }) => {
      setUpdateDataSql(sql);
      executeSQLRef.current?.getMonacoEditorRef()?.setValue(sql || '', 'cover');
    });
  };

  const handleExecuteSql = ({ operations, resultData, callback }) => {
    if (executePendingRef.current || !canExecuteSQL()) {
      return;
    }
    executePendingRef.current = true;
    callback?.(true);
    Promise.resolve()
      .then(() => getUpdateDataSql({ operations, resultData }))
      .then((res) => executeSQL(res))
      .then((_res) => {
        if (_res?.[0]?.success === true) {
          onExecuteSuccess?.();
          setViewUpdateDataSqlModal(false);
          setUpdateDataSql('');
        } else {
          onExecuteError?.(_res?.[0]?.message || '');
        }
      })
      .catch((error) => {
        onExecuteError?.(getRequestErrorMessage(error));
      })
      .finally(() => {
        executePendingRef.current = false;
        callback?.(false);
      });
  };

  const executeSuccessCallBack = () => {
    onExecuteSuccess?.();
    setViewUpdateDataSqlModal(false);
    setUpdateDataSql('');
  };

  useImperativeHandle(ref, () => ({
    handleViewSQL,
    handleExecuteSql,
  }));

  return (
    <>
      <Modal
        width="60vw"
        maskClosable={false}
        title={i18n('editTable.title.sqlPreview')}
        open={viewUpdateDataSqlModal}
        footer={false}
        destroyOnClose={true}
        onCancel={() => {
          setViewUpdateDataSqlModal(false);
          setUpdateDataSql('');
        }}
      >
        <ExecuteSQL
          initSql={updateDataSql}
          ref={executeSQLRef}
          databaseBaseInfo={{
            databaseName: executeSqlParams?.databaseName,
            dataSourceId: executeSqlParams?.dataSourceId,
            schemaName: executeSqlParams?.schemaName,
            // tableName: resultData?.tableName,
            databaseType: executeSqlParams?.databaseType,
          }}
          executeSuccessCallBack={executeSuccessCallBack}
          executeSqlApi="executeUpdateDataSql"
        />
      </Modal>
    </>
  );
});

export default memo(SQLPreviewExecute);
