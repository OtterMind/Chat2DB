import { useEffect, useRef, useState } from 'react';
import { useStyles } from './style';
import { Button, Form, Table, Tag } from 'antd';
import { CopyButton, IconButton, Input, Modal, staticMessage } from '@chat2db/ui';
import { RefreshCcw } from 'lucide-react';
import { InvitationOrderVO, InvitationStatusCode } from '@/typings/invitation';
import invitationService from '@/service/invitation';
import dayjs from 'dayjs';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';
import { openWebPage } from '@/utils/url';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

const InvitationStatus = {
  /** is withdrawing */
  [InvitationStatusCode.WITHDRAWING]: i18n('invite.status.withdrawing'),
  /** has been withdrawn */
  [InvitationStatusCode.WITHDRAWED]: i18n('invite.status.withdrawed'),
  /** pending withdrawal  */
  [InvitationStatusCode.WAIT_WITHDRAW]: i18n('invite.status.waitWithdraw'),
};

export const formatPrice = (price?: string | number) => {
  if (!price) return 0;
  // Keep two decimal places
  return (Number(price) / 100).toFixed(2);
};

const Invite = () => {
  const { styles } = useStyles();
  const [invitationCode, setInvitationCode] = useState<string>('');
  const [openModal, setOpenModal] = useState(false);
  const [modalInputValue, setModalInputValue] = useState<string>('');
  const [invitationOrder, setInvitationOrder] = useState<InvitationOrderVO | null>(null);
  const [openPayoutsModal, setOpenPayoutsModal] = useState(false);
  const [loading, setLoading] = useState(false);
  const { appUrlConfig } = useGlobalStore((state) => ({
    appUrlConfig: state.appUrlConfig,
  }));
  const [form] = Form.useForm();
  const requestGenerationRef = useRef(0);

  useEffect(() => {
    queryInvitationCode();
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  useEffect(() => {
    if (!invitationCode) return;

    queryInvitationList();
  }, [invitationCode]);

  /**
   * Get invitation code
   */
  const queryInvitationCode = async () => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    const code = await invitationService.getMyInvitationCode();
    if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
    setInvitationCode(code);
  };

  const queryInvitationList = async () => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    setLoading(true);
    try {
      const res = await invitationService.getInvitationOrderItem();
      if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
      setInvitationOrder(res);
    } catch {
      return;
    } finally {
      if (isLatestRequest(requestGenerationRef, requestGeneration)) {
        setLoading(false);
      }
    }
  };

  const renderInvitationCode = () => {
    if (!invitationCode) {
      return (
        <Button type="primary" onClick={() => setOpenModal(true)}>
          {i18n('invite.setting.createInviteCode')}
        </Button>
      );
    }
    return (
      <div className={styles.codeValue}>
        <span>{invitationCode}</span>
        <CopyButton
          size="sm"
          copyContent={invitationCode}
          copySuccessText={i18n('common.button.copySuccessfully')}
        />
        <CopyButton
          code="icon-share"
          size="sm"
          copyContent={i18n('invite.share.text', invitationCode)}
          copySuccessText={i18n('common.button.copySuccessfully')}
        />
      </div>
    );
  };

  const getStatusColor = (status: InvitationStatusCode): string => {
    switch (status) {
      case InvitationStatusCode.WITHDRAWING:
        return 'blue';
      case InvitationStatusCode.WITHDRAWED:
        return 'green';
      case InvitationStatusCode.WAIT_WITHDRAW:
        return 'orange';
      default:
        return 'default';
    }
  };

  return (
    <div className={styles.wrapper}>
      <section className={styles.inviteToolbar} data-setting-search-id="invite.code">
        <div className={styles.inviteCodeMeta}>
          <div className={styles.sectionTitle} data-setting-search-title="true">
            {i18n('invite.setting.inviteCode')}
          </div>
          <Button
            size="small"
            type="link"
            onClick={() => {
              openWebPage('https://docs.chat2db-ai.com/docs/settings/invite', '_blank');
            }}
          >
            {i18n('invite.setting.checkRule')}
          </Button>
        </div>
        <div className={styles.toolbarActions}>
          {renderInvitationCode()}
          <Button type="primary" onClick={() => setOpenPayoutsModal(true)}>
            {i18n('invite.setting.toWithdraw')}
          </Button>
        </div>
      </section>

      <div className={styles.amountWrapper} data-setting-search-id="invite.balance">
        <div className={styles.amountItem}>
          <div className={styles.amountCount}>
            {appUrlConfig.CURRENCY_SYMBOL}
            {formatPrice(invitationOrder?.totalAmount) ?? '-'}{' '}
          </div>
          <div className={styles.amountTitle}> {i18n('invite.setting.totalAssets')} </div>
        </div>
        <div className={styles.amountItem}>
          <div className={styles.amountCount}>
            {appUrlConfig.CURRENCY_SYMBOL}
            {formatPrice(invitationOrder?.withdrawAmount) ?? '-'}{' '}
          </div>
          <div className={styles.amountTitle}> {i18n('invite.setting.withdrawnAmount')} </div>
        </div>
        <div className={styles.amountItem}>
          <div className={styles.amountCount}>
            {appUrlConfig.CURRENCY_SYMBOL}
            {formatPrice(invitationOrder?.withdrawingAmount) ?? '-'}{' '}
          </div>
          <div className={styles.amountTitle}> {i18n('invite.setting.withdrawing')} </div>
        </div>
        <div className={styles.amountItem}>
          <div className={styles.amountCount}>
            {appUrlConfig.CURRENCY_SYMBOL}
            {formatPrice(invitationOrder?.canWithdrawAmount) ?? '-'}{' '}
          </div>
          <div className={styles.amountTitle}> {i18n('invite.setting.withdrawable')} </div>
        </div>
        <div className={styles.amountItem}>
          <div className={styles.amountCount}>
            {appUrlConfig.CURRENCY_SYMBOL}
            {formatPrice(invitationOrder?.waitWithdrawAmount) ?? '-'}{' '}
          </div>
          <div className={styles.amountTitle}> {i18n('invite.setting.waitWithdraw')} </div>
        </div>
      </div>

      <section className={styles.inviteListWrapper} data-setting-search-id="invite.list">
        <div className={styles.inviteTitle}>
          <span data-setting-search-title="true">{i18n('invite.setting.inviteList')}</span>
          <IconButton
            icon={RefreshCcw}
            size="sm"
            onClick={() => {
              queryInvitationList();
            }}
          />
        </div>

        <Table
          dataSource={invitationOrder?.invitationOrderItems}
          pagination={false}
          loading={loading}
          scroll={{ x: 760 }}
        >
          <Table.Column
            title={i18n('invite.setting.invitedUser')}
            dataIndex="invitationUserDisplayName"
            key="invitationUserDisplayName"
          />
          <Table.Column title={i18n('invite.setting.subscribedProduct')} dataIndex="productName" key="productName" />
          <Table.Column
            title={i18n('invite.setting.inviteTime')}
            dataIndex="createTime"
            key="createTime"
            render={(v) => {
              return dayjs(v).format('YYYY-MM-DD HH:mm:ss');
            }}
          />
          <Table.Column
            title={i18n('invite.setting.rewardAmount')}
            dataIndex="amount"
            key="amount"
            render={(v) => `${appUrlConfig.CURRENCY_SYMBOL}${formatPrice(v)}`}
          />
          <Table.Column
            title={i18n('invite.setting.inviteStatus')}
            dataIndex="status"
            key="status"
            render={(v) => <Tag color={getStatusColor(v)}>{InvitationStatus[v]}</Tag>}
          />
        </Table>
      </section>

      <Modal
        title={i18n('invite.setting.createInviteCode')}
        width={340}
        open={openModal}
        onCancel={() => {
          setOpenModal(false);
          setModalInputValue('');
        }}
        maskClosable={false}
        onOk={async () => {
          if (modalInputValue.length !== 6) {
            staticMessage.error(i18n('invite.setting.inviteCodeLength'));
            return;
          }

          const res = await invitationService.createInvitationCode({ code: modalInputValue });
          if (res) {
            setInvitationCode(modalInputValue);
            staticMessage.success(i18n('invite.setting.createInviteCodeSuccess'));
          } else {
            staticMessage.error(i18n('invite.setting.createInviteCodeFail'));
            return;
          }

          setOpenModal(false);
        }}
        okText={i18n('common.button.confirm')}
        cancelText={i18n('common.button.cancel')}
      >
        <Input
          placeholder={i18n('invite.setting.inputInviteCode')}
          value={modalInputValue}
          onChange={(e) => setModalInputValue(e.target.value)}
          maxLength={6}
          suffix={
            <IconButton
              icon={RefreshCcw}
              size="sm"
              onClick={() => {
                const code = generateInviteCode(6);
                setModalInputValue(code);
              }}
            />
          }
        />
      </Modal>

      <Modal
        title={i18n('invite.setting.withdrawal.title')}
        open={openPayoutsModal}
        onCancel={() => {
          setOpenPayoutsModal(false);
        }}
        maskClosable={false}
        onOk={async () => {
          const values = form.getFieldsValue();
          const res = await invitationService.withdrawInvitationIncome(values);
          if (res) {
            staticMessage.success(i18n('invite.setting.withdrawal.tip'));
            queryInvitationList();
            setOpenPayoutsModal(false);
          }
        }}
        okText={i18n('common.button.confirm')}
        cancelText={i18n('common.button.cancel')}
        width={600}
        destroyOnClose
      >
        <Form layout="vertical" form={form} autoComplete="off">
          <Form.Item
            label={i18n('invite.setting.withdrawal.name')}
            name="name"
            rules={[
              { required: true, message: i18n('invite.setting.withdrawal.name.required') },
              { min: 2, message: i18n('invite.setting.withdrawal.name.min') },
              { max: 50, message: i18n('invite.setting.withdrawal.name.max') },
            ]}
          >
            <Input placeholder={i18n('invite.setting.withdrawal.name.placeholder')} />
          </Form.Item>

          <Form.Item
            label={i18n('invite.setting.withdrawal.id')}
            name="cardNumber"
            rules={[
              { required: true, message: i18n('invite.setting.withdrawal.id.required') },
              { len: 18, message: i18n('invite.setting.withdrawal.id.max') },
            ]}
          >
            <Input placeholder={i18n('invite.setting.withdrawal.id.placeholder')} />
          </Form.Item>

          <Form.Item
            label={i18n('invite.setting.withdrawal.aliPay')}
            name="aliPayAccount"
            rules={[{ required: true, message: i18n('invite.setting.withdrawal.aliPay.required') }]}
          >
            <Input placeholder={i18n('invite.setting.withdrawal.aliPay.placeholder')} />
          </Form.Item>

          <Form.Item
            label={i18n('invite.setting.withdrawal.phone')}
            name="phoneNum"
            rules={[
              { required: true, message: i18n('invite.setting.withdrawal.phone.required') },
              { pattern: /^1[3-9]\d{9}$/, message: i18n('invite.setting.withdrawal.phone.pattern') },
            ]}
          >
            <Input placeholder={i18n('invite.setting.withdrawal.phone.placeholder')} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default Invite;

// Generate a random six-character invitation code from [a-z][A-Z][0-9].
function generateInviteCode(length = 6) {
  const characters = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  let inviteCode = '';
  for (let i = 0; i < length; i++) {
    const randomIndex = Math.floor(Math.random() * characters.length);
    inviteCode += characters[randomIndex];
  }
  return inviteCode;
}
