// Pinned form
import { useEffect, useState } from 'react';
import mysqlService from '@/service/sql';
import { createStyles } from 'antd-style';
import SQLPreview from '@/components/SQLPreview';
import { openModal } from '@/store/common/components';
import { buildWorkspaceObjectTabTitle } from '@/utils/workspaceObjectTabTitle';

export const useStyles = createStyles(({ css }) => {
  return {
    previewBox: css`
      margin: -0px -24px -16px;
      border-radius: 4px;
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
  const [sql, setSql] = useState('');

  useEffect(() => {
    let active = true;
    getSql().then((res) => {
      if (active) {
        setSql(res || '');
      }
    });
    return () => {
      active = false;
    };
  }, [getSql]);

  return (
    <div className={styles.previewBox}>
      <SQLPreview sql={sql} source="tree-view-ddl-modal" foldable />
    </div>
  );
};
