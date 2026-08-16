import { useEffect, useMemo, useRef, useState } from 'react';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';
import orgService from '@/service/enterprise/organization';
import { PlusOutlined, CopyOutlined, LinkOutlined } from '@ant-design/icons';
import { IOrganizationUserVO, OrgUserRoleCode, OrganizationStatusType } from '@/typings/enterprise/organization';
import { Button, Flex, Input, Modal, Popconfirm, Tag } from 'antd';
import dayjs from 'dayjs';
import { copyToClipboard } from '@/utils';
import { useUserStore } from '@/store/user';
import i18n from '@/i18n';
import { useStyles } from './style';
import { useOrgStore } from '@/store/organization';
import PageTitle from '@/components/PageTitle';
import { useGlobalStore } from '@/store/global';
import { updateUrl } from '@/utils/url';
import { SubscriptionType } from '@/constants/subscriptionType';
import AntdTable from '@/components/AntdTable';

/** Maximum number of free-trial users. */
const MAX_FREE_TRIAL_MEMBER_COUNT = 3;

function UserList() {
  const { appConfig } = useGlobalStore((state) => ({
    appConfig: state.appConfig,
  }));

  const { curOrg } = useOrgStore((state) => ({
    curOrg: state.curOrg,
  }));

  const { curUser, setPricingModalStatus, setSubscriptType } = useUserStore((state) => ({
    curUser: state.curUser,
    setPricingModalStatus: state.setPricingModalStatus,
    setSubscriptType: state.setSubscriptType,
  }));
  const { styles } = useStyles();

  const [pagination, setPagination] = useState({
    searchKey: '',
    current: 1,
    pageSize: 10,
    total: 0,
  });

  const [personList, setPersonList] = useState<IOrganizationUserVO[]>([]);
  const [isModalVisible, setIsModalVisible] = useState(false);
  const [inviteLink, setInviteLink] = useState('');
  const [orgMemberCount, setOrgMemberCount] = useState<{
    currentMemberCount: number;
    seats: number;
  }>();

  const requestGenerationRef = useRef(0);

  useEffect(() => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    orgService.queryOrgDetail().then((res) => {
      if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
      if (res) {
        setOrgMemberCount({
          currentMemberCount: res.currentMemberCount,
          seats: res.seats,
        });
      }
    });
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const findHighRole = (roleCodes: string[]) => {
    if (roleCodes.includes(OrgUserRoleCode.SUPER_ADMIN)) {
      return OrgUserRoleCode.SUPER_ADMIN;
    }
    if (roleCodes.includes(OrgUserRoleCode.ADMIN)) {
      return OrgUserRoleCode.ADMIN;
    }
    if (roleCodes.includes(OrgUserRoleCode.OPERATOR)) {
      return OrgUserRoleCode.OPERATOR;
    }
    return OrgUserRoleCode.OPERATOR;
  };

  const renderTableOpt = (item: IOrganizationUserVO) => {
    const { roleCodes: myRoleCode = [] } = curOrg || {};
    const { roleCodes } = item;

    if (!item || !myRoleCode?.length || !roleCodes.length) {
      return;
    }

    const deleteOpt = (
      <Popconfirm
        title={i18n('team.member.action.removeUser.secondConfirm')}
        onConfirm={async () => {
          await orgService.removeUser({
            organizationId: curOrg!.id,
            userId: item.id,
            roleCode: OrgUserRoleCode.OPERATOR,
          });
          queryUserList();
        }}
        onCancel={() => {}}
        okText="Yes"
        cancelText="No"
      >
        <Button type="link" size="small" style={{ marginRight: '8px' }}>
          {i18n('team.member.action.removeUser')}
        </Button>
      </Popconfirm>
    );

    const upgradeAdminOpt = (
      <Popconfirm
        title={i18n('team.member.action.setAdmin.secondConfirm')}
        onConfirm={async () => {
          await orgService.addAdminUser({ organizationId: curOrg!.id, userId: item.id });
          queryUserList();
        }}
        okText="Yes"
        cancelText="No"
      >
        <Button type="link" size="small" style={{ marginRight: '8px' }}>
          {i18n('team.member.action.setAdmin')}
        </Button>
      </Popconfirm>
    );
    const downgradeAdminOpt = (
      <Popconfirm
        title={i18n('team.member.action.removeAdmin.secondConfirm')}
        onConfirm={async () => {
          await orgService.updateUserRole({
            organizationId: curOrg!.id,
            userId: item.id,
            originalRoleCode: OrgUserRoleCode.ADMIN,
            targetRoleCode: OrgUserRoleCode.OPERATOR,
          });
          queryUserList();
        }}
        okText="Yes"
        cancelText="No"
      >
        <Button type="link" size="small" style={{ marginRight: '8px' }}>
          {i18n('team.member.action.removeAdmin')}
        </Button>
      </Popconfirm>
    );

    let optArr: Array<any> = [];
    const myHighRoleCode = findHighRole(myRoleCode);
    const highRoleCode = findHighRole(roleCodes);
    if (myHighRoleCode === OrgUserRoleCode.ADMIN || myHighRoleCode === OrgUserRoleCode.SUPER_ADMIN) {
      if (highRoleCode === OrgUserRoleCode.ADMIN) {
        optArr = [downgradeAdminOpt, deleteOpt];
      } else if (highRoleCode === OrgUserRoleCode.OPERATOR) {
        optArr = [upgradeAdminOpt, deleteOpt];
      }
    }

    // Do not show actions for the current user.
    if (item.id === curUser?.id) {
      optArr = [];
    }
    return <div>{(optArr || []).map((i) => i)}</div>;
  };

  const columns = useMemo(
    () => [
      {
        title: i18n('team.member.table.name'),
        dataIndex: 'displayName',
        key: 'displayName',
      },
      {
        title: i18n('team.member.table.role'),
        dataIndex: 'roleCodes',
        key: 'roleCodes',
        render: (roleCodes) => {
          const renderTab = (roleCode) => {
            let color = '';
            let roleName = '';
            switch (roleCode) {
              case OrgUserRoleCode.SUPER_ADMIN:
                color = 'red';
                roleName = i18n('team.member.role.superAdmin');
                break;
              case OrgUserRoleCode.ADMIN:
                color = 'blue';
                roleName = i18n('team.member.role.admin');
                break;
              case OrgUserRoleCode.OPERATOR:
                color = 'green';
                roleName = i18n('team.member.role.member');
                break;
              default:
                break;
            }
            return <Tag color={color}>{roleName}</Tag>;
          };
          return (roleCodes || [])?.map((roleCode) => renderTab(roleCode));
        },
      },
      {
        title: i18n('team.member.table.status'),
        dataIndex: 'status',
        key: 'status',
        render: (status: OrganizationStatusType) => {
          return (
            <Tag color={OrganizationStatusType.VALID ? 'green' : 'cyan'}>
              {status === OrganizationStatusType.VALID
                ? i18n('team.member.status.valid')
                : i18n('team.member.status.invalid')}
            </Tag>
          );
        },
      },
      {
        title: i18n('team.member.table.lastModified'),
        dataIndex: 'modifyTime',
        key: 'modifyTime',
        render: (item) => {
          return dayjs(item).format('YYYY-MM-DD HH:mm:ss');
        },
      },
      {
        title: i18n('team.member.table.action'),
        dataIndex: 'opt',
        key: 'opt',
        width: 200,
        render: (_, record) => renderTableOpt(record),
      },
    ],
    [curOrg, pagination],
  );

  useEffect(() => {
    queryUserList();
  }, [curOrg?.id, pagination.current, pagination.pageSize]);

  const queryUserList = async () => {
    if (curOrg?.id) {
      const res = await orgService.getOrganizationUserList({
        organizationId: curOrg.id,
        pageNo: pagination.current,
        pageSize: pagination.pageSize,
        searchKey: pagination.searchKey,
      });
      if (res) {
        setPersonList(res?.data);
        setPagination({
          ...pagination,
          total: res.total,
        });
      }
    }
  };

  const renderModalContent = () => {
    return (
      <div>
        <div className={styles.modalTitle}>{i18n('team.member.invite.link')}</div>
        <Input
          readOnly
          value={inviteLink}
          addonBefore={<LinkOutlined />}
          addonAfter={
            <CopyOutlined
              onClick={() => {
                copyToClipboard(inviteLink);
              }}
              className={styles.optIcon}
            />
          }
        />
      </div>
    );
  };

  const handleTableChange = (p) => {
    setPagination({
      ...pagination,
      ...p,
    });
  };

  return (
    <div className={styles.wrapper}>
      <PageTitle title={i18n('team.nav.member.management')} />
      <div className={styles.tableTop}>
        {/* <Input.Search
            maxLength={50}
            style={{ width: '320px' }}
            placeholder={'Search members'}
            // onSearch={handleSearch}
            enterButton={<SearchOutlined />}
          /> */}
        <div />
        {/* Show the invite button only for Enterprise. */}
        <Flex align="center" gap={8}>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={
              orgMemberCount
                ? orgMemberCount?.currentMemberCount >= (orgMemberCount?.seats || MAX_FREE_TRIAL_MEMBER_COUNT)
                : true
            }
            onClick={async () => {
              // form.resetFields();
              // setModalType(ModalType.INVITE);
              if (!curOrg?.id) {
                return;
              }
              setIsModalVisible(true);

              const link = updateUrl(appConfig.appUrl, '/invite', {
                organizationCode: curOrg.organizationCode,
                inviterId: curUser?.id,
              });
              setInviteLink(link);
            }}
          >
            {curOrg?.currentMemberCount}/{curOrg?.seats || MAX_FREE_TRIAL_MEMBER_COUNT} {i18n('team.member.invite')}
          </Button>
          {curOrg?.vip && (
            <Button
              onClick={() => {
                setPricingModalStatus(true);
                setSubscriptType(SubscriptionType.TeamAddSeat);
              }}
            >
              {i18n('team.member.upgrade')}
            </Button>
          )}
        </Flex>
      </div>
      <AntdTable
        className={styles.antdTable}
        size="large"
        columns={columns}
        dataSource={personList}
        pagination={pagination}
        onChange={handleTableChange}
        rowKey={(record) => record.id}
      />
      <Modal
        maskClosable={false}
        open={isModalVisible}
        onOk={() => setIsModalVisible(false)}
        onCancel={() => setIsModalVisible(false)}
      >
        {renderModalContent()}
      </Modal>
    </div>
  );
}

export default UserList;
