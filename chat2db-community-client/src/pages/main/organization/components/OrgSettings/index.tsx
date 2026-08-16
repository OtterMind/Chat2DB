import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Flex, Form, Input, Modal } from 'antd';
import { OrgUserRoleCode, OrganizationType } from '@/typings/enterprise/organization';
import orgService from '@/service/enterprise/organization';

import { useOrgStore } from '@/store/organization';
import PlanBox from '@/components/PlanBox';
import { useStyles } from './style';
import PageTitle from '@/components/PageTitle';
import { Select, staticMessage } from '@chat2db/ui';
import { useUserStore } from '@/store/user';
import Avatar from '@/components/Avatar';
import { refreshPage } from '@/utils';
import i18n from '@/i18n';
import CopyContainer from '@/components/CopyContainer';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

enum ModalType {
  TRANSFER = 'TRANSFER',
  EDITOR = 'EDITOR',
  TOKEN = 'TOKEN',
}
type OptionType = Array<{ label: string; value: number }>;

function OrgSetting() {
  const { styles } = useStyles();

  const [isModalVisible, setIsModalVisible] = useState(false);
  const [modalType, setModalType] = useState<ModalType>(); // 0: transfer organization; 1: invite members.
  const [form] = Form.useForm();

  const { queryOrgList, curOrg } = useOrgStore((state) => ({
    queryOrgList: state.queryOrgList,
    curOrg: state.curOrg,
  }));
  const queryCurUser = useUserStore((state) => state.queryCurUser);

  const [userList, setUserList] = useState<OptionType>([]);
  const [userOptions, setUserOptions] = useState<OptionType>([]);
  const requestGenerationRef = useRef(0);

  // Hooks must run unconditionally on every render. The early return below
  // previously sat above useMemo/useEffect, causing a Rules-of-Hooks violation
  // ("Rendered more hooks than during the previous render") when curOrg
  // transitioned from null to populated. Guard inside the hooks instead.
  const isOwner = useMemo(
    () => curOrg?.roleCodes?.includes(OrgUserRoleCode.SUPER_ADMIN) ?? false,
    [curOrg],
  );

  useEffect(() => {
    if (!curOrg) return;
    queryUserList();
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, [curOrg]);

  if (!curOrg) return null;

  const queryUserList = async () => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    const res = await orgService.getOrganizationUserList({
      organizationId: curOrg?.id,
      searchKey: '',
      pageNo: 1,
      pageSize: 20,
    });
    if (res) {
      const options = (res?.data || []).reduce((acc: OptionType, cur) => {
        if (cur.id === curOrg?.ownerId) return acc;
        acc.push({
          label: cur.displayName,
          value: cur.id,
        });
        return acc;
      }, []);
      if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
      setUserList(options);
      setUserOptions(options);
    }
  };

  const handleSubmitModal = async () => {
    await form.validateFields();
    const values = form.getFieldsValue();

    if (modalType === ModalType.EDITOR) {
      const res = await orgService.updateOrganization({ id: curOrg?.id, name: values.name });
      if (res) {
        staticMessage.success(i18n('common.message.modifySuccessfully'));
        setIsModalVisible(false);
        queryOrgList();
        queryCurUser();
      }
    } else if (modalType === ModalType.TRANSFER) {
      const res = await orgService.transferOwner({ id: curOrg?.id, newOwnerId: values.newOwnerId });
      if (res) {
        staticMessage.success(i18n('team.setting.transfer.successfully'));
        refreshPage();
      }
    }
    //else if (modalType === ModalType.INVITE) {
    // }
    // setIsModalVisible(false);
  };

  const handleCancelModal = () => {
    setIsModalVisible(false);
    form.resetFields();
  };

  const handleSearch = async (value?: string) => {
    if (!value) return setUserOptions(userList);

    const options = userList.filter((v) => {
      return v.label.includes(value);
    });
    setUserOptions(options);
  };

  const renderModalContent = () => {
    if (modalType === ModalType.EDITOR) {
      return (
        <div className={styles.modalContent}>
          <div className={styles.modalTitle}>
            {i18n('team.setting.button.edit')}
            {i18n('team.setting.name')}
          </div>
          <Form form={form} layout={'vertical'} className={styles.modalForm}>
            <Form.Item
              name={'name'}
              label={i18n('team.setting.name')}
              rules={[{ required: true, message: 'Please type your orgization name!' }]}
            >
              <Input maxLength={50} defaultValue={curOrg?.name} />
            </Form.Item>
          </Form>
        </div>
      );
    } else if (modalType === ModalType.TOKEN) {
      return (
        <div className={styles.modalContent}>
          {/* <div className={styles.modalTitle}>Token information</div>
          <Form form={form} layout={'vertical'} className={styles.modalForm}>
            <Form.Item name={'token'} label="TOKEN">
              <Input value={curOrgToken} addonAfter={<CopyOutlined style={{ cursor: 'pointer' }} />} />
            </Form.Item>
            <Form.Item name={'nonExpire'} label="Never expires">
              <Radio.Group>
                <Radio value={'Y'}>Yes</Radio>
                <Radio value={'N'}>No</Radio>
              </Radio.Group>
            </Form.Item>
            {nonExpire === 'N' && (
              <Form.Item name={'endTime'} label="End time">
                <DatePicker />
              </Form.Item>
            )}
          </Form> */}
        </div>
      );
    } else if (modalType === ModalType.TRANSFER) {
      return (
        <div className={styles.modalContent}>
          <div className={styles.modalTitle}>{i18n('team.setting.button.transfer')}</div>
          <div className={styles.modalDesc}>{i18n('team.setting.transfer.desc')}</div>
          <Form form={form} layout={'vertical'} className={styles.modalForm}>
            <Form.Item name={'newOwnerId'} rules={[{ required: true, message: 'Please type your orgization name!' }]}>
              <Select
                showSearch
                maxLength={50}
                style={{ width: '320px' }}
                placeholder={i18n('team.setting.transfer.search')}
                filterOption={false}
                onSearch={handleSearch}
                options={userOptions}
              />
            </Form.Item>
            {/* <div className={styles.modalInput}></div> */}
          </Form>
        </div>
      );
    }
  };

  return (
    <Flex vertical className={styles.flex}>
      <PageTitle title={i18n('team.nav.team.setting')} />
      <PlanBox />
      <div className={styles.header}>{i18n('team.setting.basic')}</div>
      <div className={styles.scrollBox}>
        <div className={styles.table}>
          <div className={styles.tableItem}>
            <div className={styles.itemName}>{i18n('team.setting.avatar')}</div>
            <div className={styles.itemDesc}>
              <Avatar
                org={curOrg}
                size={56}
                canEditor={
                  (curOrg?.roleCodes || [])?.includes(OrgUserRoleCode.SUPER_ADMIN) ||
                  (curOrg?.roleCodes || []).includes(OrgUserRoleCode.ADMIN)
                }
              />
            </div>
          </div>
          <div className={styles.tableItem}>
            <div className={styles.itemName}>{i18n('team.setting.name')}</div>
            <div className={styles.itemDesc}>{curOrg?.name}</div>
            <div className={styles.itemOpt}>
              <Button
                type="default"
                disabled={!isOwner}
                onClick={() => {
                  setIsModalVisible(true);
                  setModalType(ModalType.EDITOR);
                }}
              >
                {i18n('team.setting.button.edit')}
              </Button>
            </div>
          </div>
          <div className={styles.tableItem}>
            <div className={styles.itemName}>{i18n('team.setting.code')}</div>
            <div className={styles.itemDesc}>
              <CopyContainer>{curOrg?.organizationCode}</CopyContainer>
            </div>
          </div>
          {/* <div className={styles.tableItem}>
            <div className={styles.name}>My organization token</div>
            <div className={styles.opt}>
              <Button type="default" size="large" onClick={handleToken}>
                View
              </Button>
            </div>
          </div> */}
        </div>

        {curOrg?.type === OrganizationType.TEAM && (
          <>
            <div className={styles.header}>{i18n('team.setting.danger')}</div>
            <div className={styles.table}>
              <div className={styles.tableItem}>
                <div className={styles.itemName}>{i18n('team.setting.transfer')}</div>
                <div className={styles.itemDesc}>{i18n('team.setting.transfer.desc')}</div>
                <div className={styles.itemOpt}>
                  <Button
                    disabled={!isOwner}
                    type="primary"
                    danger
                    onClick={() => {
                      setIsModalVisible(true);
                      setModalType(ModalType.TRANSFER);
                      handleSearch();
                    }}
                  >
                    {i18n('team.setting.transfer')}
                  </Button>
                </div>
              </div>
            </div>
          </>
        )}
      </div>

      <Modal maskClosable={false} open={isModalVisible} onOk={handleSubmitModal} onCancel={handleCancelModal}>
        {renderModalContent()}
      </Modal>
    </Flex>
  );
}

export default OrgSetting;
