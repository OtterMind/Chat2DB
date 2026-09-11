import { useEffect, useRef, useState } from 'react';
import { useWorkspaceStore } from '@/store/workspace';
import { useTreeStore } from '@/store/tree';
import { GlobalComponents } from '../config';
import ViewDDL from '@/components/ViewDDL';
import { useStyles } from './style';
import { TreeNodeType } from '@/constants';
import { Empty } from '@chat2db/ui';
import SQLPreview from '@/components/SQLPreview';
import { Spin } from 'antd';
import i18n from '@/i18n';
import type { TreeNodeData } from '@/typings/tree';
import accountAdminService from '@/service/accountAdmin';
import { invalidateLatestRequest } from '@/utils/latestRequest';
import { loadLatestAccountGrants } from './accountGrantsRequest';

const GlobalExtendComponents = () => {
  const { styles } = useStyles();
  const { currentWorkspaceGlobalExtend, setCurrentWorkspaceGlobalExtend } = useWorkspaceStore((state) => {
    return {
      currentWorkspaceGlobalExtend: state.currentWorkspaceGlobalExtend,
      setCurrentWorkspaceGlobalExtend: state.setCurrentWorkspaceGlobalExtend,
    };
  });
  const currentTreeNode = useTreeStore((state) => state.currentTreeNode);

  useEffect(() => {
    if (isDDLObjectTreeNode(currentTreeNode?.treeNodeType)) {
      const objectName = getDDLObjectName(currentTreeNode);
      if (!objectName) {
        setCurrentWorkspaceGlobalExtend(null);
        return;
      }
      setCurrentWorkspaceGlobalExtend({
        code: GlobalComponents.view_ddl,
        uniqueData: {
          ...(currentTreeNode.extraParams || {}),
          treeNodeType: currentTreeNode.treeNodeType,
          objectName,
        },
      });
      return;
    }
    if (currentTreeNode?.treeNodeType === TreeNodeType.DATABASE_ACCOUNT) {
      const { dataSourceId, user, host } = currentTreeNode.extraParams || {};
      if (!dataSourceId || !user || !host) {
        setCurrentWorkspaceGlobalExtend(null);
        return;
      }
      setCurrentWorkspaceGlobalExtend({
        code: GlobalComponents.account_grants,
        uniqueData: {
          dataSourceId,
          user,
          host,
          objectName: currentTreeNode.originalTitle,
        },
      });
      return;
    }
    setCurrentWorkspaceGlobalExtend(null);
  }, [currentTreeNode, setCurrentWorkspaceGlobalExtend]);

  useEffect(() => {
    return () => {
      setCurrentWorkspaceGlobalExtend(null);
    };
  }, [setCurrentWorkspaceGlobalExtend]);

  switch (currentWorkspaceGlobalExtend?.code) {
    case GlobalComponents.view_ddl: {
      const objectName = currentWorkspaceGlobalExtend.uniqueData.objectName;
      return (
        <div className={styles.viewDDLBox}>
          <div className={styles.viewDDLHeader}>{`${objectName}-DDL`}</div>
          <ViewDDL data={currentWorkspaceGlobalExtend.uniqueData} />
        </div>
      );
    }
    case GlobalComponents.account_grants:
      return <AccountGrants data={currentWorkspaceGlobalExtend.uniqueData} />;
    default:
      return (
        <div className={styles.noInformation}>
          <Empty title={i18n('workspace.text.noInformation')} />
        </div>
      );
  }
};

export default GlobalExtendComponents;

interface AccountGrantsProps {
  data: {
    dataSourceId: number;
    user: string;
    host: string;
    objectName?: string;
  };
}

const AccountGrants = ({ data }: AccountGrantsProps) => {
  const { styles } = useStyles();
  const [loading, setLoading] = useState(false);
  const [grants, setGrants] = useState<string[]>([]);
  const requestGenerationRef = useRef(0);

  useEffect(() => {
    if (!data?.dataSourceId || !data.user || !data.host) {
      setGrants([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    void loadLatestAccountGrants(
      requestGenerationRef,
      () =>
        accountAdminService.grants({
          dataSourceId: data.dataSourceId,
          user: data.user,
          host: data.host,
        }),
      setGrants,
      () => setLoading(false),
    );
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, [data?.dataSourceId, data?.user, data?.host]);

  return (
    <div className={styles.viewDDLBox}>
      <div className={styles.viewDDLHeader}>{data.objectName || `${data.user}@${data.host}`}</div>
      <Spin spinning={loading} wrapperClassName={styles.grantsSpin}>
        {grants.length ? (
          <div className={styles.grantsContent}>
            <SQLPreview sql={grants.join('\n')} source="database-account-grants" foldable={false} />
          </div>
        ) : (
          <div className={styles.noInformation}>
            <Empty title={i18n('workspace.text.noInformation')} />
          </div>
        )}
      </Spin>
    </div>
  );
};

const isDDLObjectTreeNode = (treeNodeType?: TreeNodeType) => {
  return (
    treeNodeType === TreeNodeType.TABLE ||
    treeNodeType === TreeNodeType.VIEW ||
    treeNodeType === TreeNodeType.FUNCTION ||
    treeNodeType === TreeNodeType.PROCEDURE
  );
};

const getDDLObjectName = (treeNodeData?: TreeNodeData | null) => {
  if (treeNodeData?.treeNodeType === TreeNodeType.FUNCTION) {
    return treeNodeData.extraParams?.functionName;
  }
  if (treeNodeData?.treeNodeType === TreeNodeType.PROCEDURE) {
    return treeNodeData.extraParams?.procedureName;
  }
  if (treeNodeData?.treeNodeType === TreeNodeType.VIEW) {
    return treeNodeData.extraParams?.viewName;
  }
  return treeNodeData?.extraParams?.tableName;
};
