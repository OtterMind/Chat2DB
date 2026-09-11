import React, { memo, useEffect } from 'react';
import { useStyles } from './style';
import classnames from 'classnames';
import sqlServer from '@/service/sql';
import { TreeNodeType } from '@/constants';
import DdlPreview from './DdlSearch/DdlPreview';
interface IProps {
  className?: string;
  data: any;
}

export default memo<IProps>((props) => {
  const { className, data } = props;
  const { styles } = useStyles();

  const [loadedDdl, setLoadedDdl] = React.useState({ key: '', sql: '' });
  const requestIdRef = React.useRef(0);
  // Scoped DDL search (Issue #2748): switching the inspected object resets it.
  // The key is built from stable identifiers so unrelated re-renders that
  // recreate the data object do not reset an in-progress search.
  const searchResetKey = data
    ? JSON.stringify([
        data.dataSourceId,
        data.databaseName,
        data.schemaName,
        data.treeNodeType,
        data.tableName,
        data.viewName,
        data.functionName,
        data.procedureName,
      ])
    : '';
  const sql = loadedDdl.key === searchResetKey ? loadedDdl.sql : '';

  useEffect(() => {
    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;

    if (!data) {
      setLoadedDdl({ key: searchResetKey, sql: '' });
      return;
    }

    setLoadedDdl({ key: searchResetKey, sql: '' });

    getDDL(data)
      .then((res) => {
        if (requestIdRef.current !== requestId) {
          return;
        }
        setLoadedDdl({ key: searchResetKey, sql: res || '' });
      })
      .catch(() => {
        // Keep current error handling behavior in the service layer.
      });
    return () => {
      requestIdRef.current += 1;
    };
  }, [data]);

  return (
    <DdlPreview
      className={classnames(styles.viewDDL, className)}
      sql={sql}
      resetKey={searchResetKey}
      source="view-ddl"
    />
  );
});

const getDDL = async (data: any) => {
  if (data.treeNodeType === TreeNodeType.VIEW) {
    if (!data.viewName) {
      return '';
    }
    const res = await sqlServer.getViewDetail({
      dataSourceId: data.dataSourceId,
      databaseName: data.databaseName,
      schemaName: data.schemaName,
      tableName: data.viewName,
    });
    return res?.ddl;
  }

  if (data.treeNodeType === TreeNodeType.FUNCTION) {
    if (!data.functionName) {
      return '';
    }
    const res = await sqlServer.getFunctionDetail({
      dataSourceId: data.dataSourceId,
      databaseName: data.databaseName,
      schemaName: data.schemaName,
      functionName: data.functionName,
    });
    return res?.functionBody;
  }

  if (data.treeNodeType === TreeNodeType.PROCEDURE) {
    if (!data.procedureName) {
      return '';
    }
    const res = await sqlServer.getProcedureDetail({
      dataSourceId: data.dataSourceId,
      databaseName: data.databaseName,
      schemaName: data.schemaName,
      procedureName: data.procedureName,
    });
    return res?.procedureBody;
  }

  if (!data.tableName) {
    return '';
  }
  return sqlServer.exportCreateTableSql({
    ...data,
  } as any);
};
