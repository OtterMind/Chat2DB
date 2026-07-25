import { useEffect, useRef, useState } from 'react';
import { ApprovalStatusMap, ApprovalStatusType, IApprovalProcessVO } from '@/typings/enterprise/approval';
import { Button, Drawer, Modal, Spin, Timeline, TimelineItemProps } from 'antd';
import dayjs from 'dayjs';
import styles from './index.less';
import approval from '@/service/enterprise/approval';
import permissionService from '@/service/enterprise/permission';
import { useUserStore } from '@/store/user';
import { useOrgStore } from '@/store/organization';
import i18n from '@/i18n';
import DataAccessForm, { IDataAccessForm } from '../../Permission/components/dataAccessForm';

interface IProps {
  approvalId?: number;
  onRefresh?: () => void;
}

function ApprovalFlow(props: IProps) {
  const userInfo = useUserStore((s) => s.curUser);
  const { isOwner } = useOrgStore((s) => ({
    isOwner: s.isOwner,
  }));
  const { approvalId } = props;
  const [approvalProcess, setApprovalProcess] = useState<IApprovalProcessVO>();
  const [loading, setLoading] = useState(false);
  const { name, approvalStatus, description } = approvalProcess || {};
  const [openApply, setOpenApply] = useState(false);
  const dataFormValuesRef = useRef<IDataAccessForm>();

  useEffect(() => {
    queryApprovalDetail();
  }, [approvalId]);

  const queryApprovalDetail = async () => {
    if (!approvalId) return;

    setLoading(true);
    try {
      const res = await approval.queryApprovalDetail({
        id: approvalId,
      });
      setApprovalProcess(res);
    } catch {
      setApprovalProcess(undefined);
    } finally {
      setLoading(false);
    }
  };

  const queryApply = async () => {
    if (!approvalProcess?.applyId) return;

    const res = await permissionService.queryApplyDetail({ id: approvalProcess?.applyId });
    if (res) {
      const {
        id,
        name: applyName,
        description: applyDescription,
        noExpire,
        validUntil,
        columnNames,
        policyVOList,
        dataAccessControlList,
        approvalId: applyApprovalId,
      } = res;
      const { dataSourceId, databaseName, tableName, schemaName, rowCount, rowFilter } =
        dataAccessControlList?.[0] || {};

      dataFormValuesRef.current = {
        id,
        name: applyName,
        description: applyDescription,
        // Check whether the authorization covers the entire database.
        isAllSchema: databaseName === 'ALL_DATABASE_GRANTED',
        dataSourceId,
        databaseInfo: {
          databaseName,
          tableName,
          schemaName,
          columnNames: (columnNames || []).includes('ALL_COLUMN_GRANTED') ? [''] : columnNames,
        },
        noExpire,
        validUntil,
        policyVOList,
        rowCount,
        rowFilter,
        approvalId: applyApprovalId,
      };
    }
    setOpenApply(true);
  };

  const renderExtra = (item: IApprovalProcessVO) => {
    const {
      approvalStatus: itemApprovalStatus,
      applyUser,
      awaitingUser,
      approveUser,
    } = item;
    //
    if (itemApprovalStatus === ApprovalStatusType.INVOKED) {
      return (
        <div>
          {i18n('team.approval.flow.list.apply')}：{applyUser?.displayName}
        </div>
      );
    } else if (itemApprovalStatus === ApprovalStatusType.PENDING) {
      return (
        <>
          <div>
            {i18n('team.approval.flow.list.approve')}：{isOwner ? userInfo?.displayName : awaitingUser?.displayName}
          </div>
          {(userInfo?.id === awaitingUser.id || isOwner) && (
            <div style={{ marginTop: '4px' }}>
              <Button
                size="small"
                type="primary"
                style={{ marginRight: '8px' }}
                onClick={() => {
                  Modal.confirm({
                    title: i18n('team.confirm.title'),
                    onOk: async () => {
                      await approval.updateApproval({
                        id: item.id,
                        approvalStatus: ApprovalStatusType.APPROVED,
                      });
                      queryApprovalDetail();
                      props.onRefresh && props.onRefresh();
                    },
                    onCancel() {
                      // console.log('Cancel');
                    },
                  });
                }}
              >
                {i18n('team.confirm')}
              </Button>
              <Button
                size="small"
                onClick={() => {
                  Modal.confirm({
                    title: i18n('team.reject.title'),
                    onOk: async () => {
                      await approval.updateApproval({
                        id: item.id,
                        approvalStatus: ApprovalStatusType.REJECTED,
                      });

                      queryApprovalDetail();
                      props.onRefresh && props.onRefresh();
                    },
                    onCancel() {
                      console.log('Cancel');
                    },
                  });
                }}
              >
                {i18n('team.reject')}
              </Button>
            </div>
          )}
        </>
      );
    } else if (itemApprovalStatus === ApprovalStatusType.APPROVED) {
      return (
        <div>
          {' '}
          {i18n('team.approval.flow.list.approve')}：{approveUser?.displayName}
        </div>
      );
    }
  };

  const renderTimeLineItems = () => {
    const itemsList: IApprovalProcessVO[] = [];
    let root = approvalProcess;
    while (root) {
      // Keep the first level fixed.
      if (root.approvalLevel === 1) {
        itemsList.push({
          ...root,
          approvalStatus: ApprovalStatusType.INVOKED,
        });
      } else {
        itemsList.push(root);
      }
      root = root?.child?.[0];
    }

    const items: TimelineItemProps[] = [];
    (itemsList || []).forEach((item, index) => {
      const greenColor = [ApprovalStatusType.INVOKED, ApprovalStatusType.APPROVED].includes(item.approvalStatus);

      items.push({
        color: greenColor ? 'green' : 'blue',
        children: (
          <div>
            <div>{ApprovalStatusMap[item.approvalStatus]}</div>
            <div> {item.name}</div>
            <div>{dayjs(index === 0 ? item.createTime : item.modifyTime).format('YYYY-MM-DD HH:mm:ss')}</div>
            {renderExtra(item)}
          </div>
        ),
      });
    });
    return items;
  };

  const renderViewApplyDetail = () => {
    if (approvalProcess?.applyType === 'JOIN_ORGANIZATION') return null;

    return (
      <>
        <Button
          type="primary"
          onClick={() => {
            queryApply();
          }}
        >
          {i18n('team.apply.view.title')}
        </Button>
        <Drawer
          width={500}
          open={openApply}
          title={i18n('team.apply.viewDataPermission')}
          onClose={() => setOpenApply(false)}
        >
          <DataAccessForm
            type="apply"
            isPreview={true}
            showApprovalFlowBtn={false}
            initData={dataFormValuesRef.current}
          />
        </Drawer>
      </>
    );
  };

  if (!approvalProcess) return null;

  return (
    <Spin spinning={loading}>
      <div className={styles.approvalFlow}>
        <div className={styles.block}>
          <div className={styles.title}>{i18n('team.approval.flow.name')}</div>
          <div className={styles.value}>{name}</div>
        </div>
        <div className={styles.block}>
          <div className={styles.title}>{i18n('team.approval.flow.curStatus')}</div>
          <div className={styles.value}>{approvalStatus ? ApprovalStatusMap[approvalStatus] : null}</div>
          {/* <Tag>{approvalStatus ? ApprovalStatusMap[approvalStatus] : null}</Tag> */}
        </div>
        <div className={styles.block}>
          <div className={styles.title}>{i18n('team.approval.flow.desc')}</div>
          <div className={styles.value}>{description}</div>
        </div>

        <div className={styles.block}>
          <div className={styles.title}>{i18n('team.approval.flow.list')}</div>
          <Timeline mode="left" items={renderTimeLineItems()} />
        </div>
      </div>
      {renderViewApplyDetail()}
    </Spin>
  );
}

export default ApprovalFlow;
