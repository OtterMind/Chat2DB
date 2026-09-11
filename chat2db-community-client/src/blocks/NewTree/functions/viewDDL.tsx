// Pinned form
import { useEffect, useState } from 'react';
import mysqlService from '@/service/sql';
import { createStyles } from 'antd-style';
import DdlPreview from '@/components/ViewDDL/DdlSearch/DdlPreview';
import { openModal } from '@/store/common/components';
import { buildWorkspaceObjectTabTitle } from '@/utils/workspaceObjectTabTitle';

export const useStyles = createStyles(({ css }) => {
  return {
    previewBox: css`
      margin: -0px -24px -16px;
      border-radius: 4px;
      max-height: 65vh;
    `,
  };
});

export const viewDDL = (treeNodeData) => {
  const { dataSourceName, databaseName, schemaName } = treeNodeData.extraParams;
  const objectTitle = buildWorkspaceObjectTabTitle({
    dataSourceName,
    databaseName,
    schemaName,
    objectName: treeNodeData.originalTitle,
  });
  const getSql = () => {
    return new Promise((resolve) => {
      mysqlService
        .exportCreateTableSql({
          dataSourceId: treeNodeData.extraParams.dataSourceId,
          databaseName: treeNodeData.extraParams.databaseName,
          schemaName: treeNodeData.extraParams.schemaName,
          tableName: treeNodeData.originalTitle,
        })
        .then((res) => {
          resolve(res);
        });
    });
  };

  openModal({
    title: `DDL - ${objectTitle}`,
    width: '60%',
    footer: false,
    content: <DDLPreviewAsync getSql={getSql} />,
  });
};

export const DDLPreviewAsync = (params: { getSql: any }) => {
  const { getSql } = params;
  const { styles } = useStyles();
  const [loadedDdl, setLoadedDdl] = useState<{ request: unknown; sql: string }>({ request: null, sql: '' });
  const sql = loadedDdl.request === getSql ? loadedDdl.sql : '';

  useEffect(() => {
    let active = true;
    setLoadedDdl({ request: getSql, sql: '' });
    getSql().then((res) => {
      if (active) {
        setLoadedDdl({ request: getSql, sql: res || '' });
      }
    });
    return () => {
      active = false;
    };
  }, [getSql]);

  return (
    <DdlPreview className={styles.previewBox} sql={sql} resetKey={getSql} source="tree-view-ddl-modal" />
  );
};
