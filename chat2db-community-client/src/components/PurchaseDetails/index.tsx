import { memo, useEffect, useState } from 'react';
import { useStyles } from './style';
import AntdTable from '@/components/AntdTable';
import i18n from '@/i18n';
import dayjs from 'dayjs';
import { CopyButton, IconButton, IconfontSvg, Modal, TextArea } from '@chat2db/ui';
import { copyToClipboard } from '@/utils';
import pricingServices from '@/service/pricing';
import { formatPriceWithCurrency } from '@/utils/price';
import LicenseService from '@/service/license';
import { Checkbox, Flex, Form, Popconfirm, Radio, Button } from 'antd';
import { MonitorOff } from 'lucide-react';
import { isDesktop } from '@/utils/env';
import miscService from '@/service/misc';
import { useGlobalStore } from '@/store/global';
import feedback from '@/utils/feedback';

interface IProps {
  className?: string;
  hideTitle?: boolean;
}

const SUBOTIZ_INVOICE_PORTAL_URL = 'https://checkout.subotiz.com/m/2821768/portal/login';

const lostQualification = [
  {
    value: 1,
    label: 'Access to AI models: Claude 3.5/3.7 Sonnet, OpenAI GPT-4o',
  },
  {
    value: 2,
    label: '1,000 AI services per month',
  },
  {
    value: 3,
    label: 'Unlimited database instances',
  },
  {
    value: 4,
    label: 'Intelligent visual management and reporting tools',
  },
  {
    value: 5,
    label: 'Visual data viewing, editing, and SQL file execution',
  },
];

const unsubscribeReason = [
  { value: 1, label: 'I only needed this service for one month' },
  { value: 2, label: 'AI responses are not satisfactory or useful for my needs' },
  { value: 3, label: 'The subscription cost is too high for me' },
  { value: 4, label: 'I have found a better alternative solution' },
  { value: 5, label: 'Technical issues' },
  { value: 6, label: 'Other (please specify below)' },
];

export default memo<IProps>((props) => {
  const { className, hideTitle = false } = props;
  const { styles, cx } = useStyles();
  const [data, setData] = useState<any[]>([]);
  const [showFeedbackModal, setShowFeedbackModal] = useState(false);
  const [showUnsubscribeModal, setShowUnsubscribeModal] = useState(false);
  const [radioGroupValue, setRadioGroupValue] = useState<number>(1);
  const isCN = useGlobalStore((state) => state.appConfig.isCN);
  const [form] = Form.useForm();

  const fetchDescription = async (record: any) => {
    try {
      const listCertificate = await LicenseService.listCertificate({ licenseId: record.licenseId });
      const findRecord = data.find((item) => item.licenseId === record.licenseId);
      if (findRecord) {
        findRecord.listCertificate = listCertificate;
      }
      setData([...data]);
    } catch (error) {
      console.error('Failed to fetch description:', error);
    }
  };

  const columns: any = [
    {
      title: i18n('setting.purchaseDetails.productName'),
      dataIndex: 'productName',
      key: 'productName',
    },
    {
      title: i18n('setting.purchaseDetails.price'),
      dataIndex: 'price',
      key: 'price',
      render: (_data, rowData) => {
        return formatPriceWithCurrency(rowData.currency, rowData.price);
      },
    },
    {
      title: i18n('setting.purchaseDetails.createTime'),
      dataIndex: 'subscriptionStartTime',
      key: 'subscriptionStartTime',
      render: (v) => {
        return dayjs(v).format('YYYY-MM-DD HH:mm:ss');
      },
    },
    {
      title: i18n('setting.purchaseDetails.expireTime'),
      dataIndex: 'subscriptionEndTime',
      key: 'subscriptionEndTime',
      render: (v) => {
        if (!v) {
          return '-';
        }
        return dayjs(v).format('YYYY-MM-DD HH:mm:ss');
      },
    },
    {
      title: i18n('setting.purchaseDetails.activationCode'),
      dataIndex: 'license',
      key: 'license',
      render: (_data) => {
        if (!_data) {
          return false;
        }
        return (
          <div className={styles.apiKeyBox}>
            <div className={styles.apiKeyText}>{_data}</div>
            <IconButton
              className={styles.iconButton}
              size="md"
              code="icon-copy"
              onClick={() => {
                copyToClipboard(_data);
              }}
            />
          </div>
        );
      },
    },
    {
      title: i18n('setting.purchaseDetails.orderStatus'),
      dataIndex: 'status',
      key: 'status',
      render: (_data) => {
        if (_data === 'ACTIVE' || _data === 'TRIAL_CREATE') {
          return (
            <div className={styles.validBox}>
              <span className={styles.valid}>
                {_data === 'TRIAL_CREATE'
                  ? i18n('setting.purchaseDetails.trial')
                  : i18n('setting.purchaseDetails.active')}
              </span>
            </div>
          );
        } else if (_data === 'EXPIRED') {
          return <span className={styles.invalid}>{i18n('setting.purchaseDetails.expired')}</span>;
        } else {
          return <span className={styles.invalid}>{i18n('setting.purchaseDetails.inactive')}</span>;
        }
      },
    },
  ];

  const getOrderList = async () => {
    const res = await pricingServices.getOrderList();
    setData(res.map((item) => ({ ...item, listCertificate: [], key: item.id })));
  };

  useEffect(() => {
    getOrderList();
  }, []);

  useEffect(() => {
    form.resetFields();
  }, [showFeedbackModal]);

  const handleFeedbackModalOk = () => {
    form
      .validateFields()
      .then((values) => {
        if (values.feedbackRadio === undefined) {
          feedback.error('Please select the reason for unsubscribing');
          return;
        }
        if (!values.feedbackContent) {
          feedback.error('Please enter the feedback content');
          return;
        }
        miscService.createFeedback({
          feedbackType: 'Function Suggestion',
          description: `${values.feedbackContent} unsubscribeReason: ${
            unsubscribeReason[values.feedbackRadio - 1].label
          }`,
          uploadLog: values.uploadLog,
        });
        setShowFeedbackModal(false);
        setShowUnsubscribeModal(true);
      })
      .catch((error) => {
        console.log(error);
      });
  };

  const handleChangeRadioGroup = (e: any) => {
    setRadioGroupValue(e.target.value);
  };

  const handleUnsubscribeModalOk = () => {
    pricingServices.cancelSubscription().then(() => {
      feedback.success('Unsubscribed successfully');
      setShowUnsubscribeModal(false);
      getOrderList();
    });
  };

  return (
    <div className={cx(styles.purchaseDetails, { [styles.withoutTitle]: hideTitle }, className)}>
      {!hideTitle && <div className={styles.title}>{i18n('setting.purchaseDetails.title')}</div>}
      <AntdTable
        className={styles.antdTableBox}
        dataSource={data}
        columns={columns}
        expandable={{
          expandedRowRender: (record) => {
            if (!record.listCertificate?.length) {
              return null;
            }
            return (
              <Flex vertical gap={6}>
                {(record.listCertificate || []).map((item, index: number) => {
                  if (item.activateType === 'ONLINE') {
                    return (
                      <div className={styles.cerTitle} key={item.deviceId}>
                        {i18n('setting.purchaseDetails.device')} {index + 1} :{' '}
                        {i18n('setting.purchaseDetails.onlineActivation')}
                        <Popconfirm
                          title={i18n('license.deactivateOnlineConfirm')}
                          onConfirm={() => {
                            LicenseService.deactivateOnline({ deviceId: item.deviceId }).then(() => {
                              feedback.success(i18n('license.deactivateOnlineSuccess'));
                              fetchDescription(record);
                            });
                          }}
                        >
                          <IconButton
                            className={styles.monitorOff}
                            size={16}
                            icon={MonitorOff}
                            title={i18n('license.deactivateOnline')}
                          />
                        </Popconfirm>
                      </div>
                    );
                  } else {
                    return (
                      <Flex vertical key={item.deviceId}>
                        <div className={styles.cerTitle}>
                          {i18n('setting.purchaseDetails.device')} {index + 1} :{' '}
                          {i18n('setting.purchaseDetails.offlineActivation')}
                        </div>
                        <div className={styles.cerContent}>
                          <div>
                            {i18n('setting.purchaseDetails.deviceName')}：{item.deviceName}
                          </div>
                          <div>
                            {i18n('setting.purchaseDetails.deviceType')}：{item.deviceType}
                          </div>
                          <Flex gap={4}>
                            {i18n('setting.purchaseDetails.deviceId')}：
                            <span>
                              {item.deviceId?.length > 20 ? `${item.deviceId.slice(0, 20)}...` : item.deviceId}
                            </span>
                            <CopyButton size={12} copyContent={item.deviceId} />
                          </Flex>
                          <Flex gap={4}>
                            {i18n('setting.purchaseDetails.certificate')}：{i18n('setting.purchaseDetails.clickToCopy')}
                            <CopyButton size={12} copyContent={item.cer} />
                          </Flex>
                        </div>
                      </Flex>
                    );
                  }
                })}
              </Flex>
            );
          },
          // rowExpandable: (record) => record.orgType === 'LOCAL' && record.type === 'FOREVER',
          rowExpandable: (record) => record.orgType === 'LOCAL',
          onExpand: (expanded, record) => {
            if (expanded && !record.listCertificate?.length) {
              fetchDescription(record);
            }
          },
        }}
      />
      {!isCN &&
        (() => {
          const hasActive = data.some((d) => d.status === 'ACTIVE' || d.status === 'TRIAL_CREATE');
          const isCancelled = data.some((d) => d.cancelled);
          return (
            <div className={styles.footerActions}>
              <Button onClick={() => window.open(SUBOTIZ_INVOICE_PORTAL_URL, '_blank', 'noopener,noreferrer')}>
                {i18n('setting.purchaseDetails.getInvoice')}
              </Button>
              {isCancelled && <Button disabled>{i18n('setting.purchaseDetails.alreadyCancelled')}</Button>}
              {!isCancelled && hasActive && (
                <Button onClick={() => setShowFeedbackModal(true)}>
                  {i18n('setting.purchaseDetails.unsubscribe')}
                </Button>
              )}
            </div>
          );
        })()}
      <Modal
        open={showFeedbackModal}
        onCancel={() => setShowFeedbackModal(false)}
        onOk={handleFeedbackModalOk}
        headerBorder
        okText="Next"
        cancelText="Cancel"
        destroyOnClose
        title={
          <div className={styles.modalHeaderBox}>
            <div className={styles.modalHeaderTitle}>
              <IconfontSvg code="icon-colourful-runny-nose-face" />
              We&apos;re sorry to see you go!
            </div>
            <div className={styles.modalHeaderSubTitle}>
              Please select the reason for canceling your Chat2DB Pro subscription
            </div>
          </div>
        }
      >
        <div className={styles.modalContentBox}>
          <Form form={form} layout="vertical">
            <Form.Item label={'Can you tell me the reason for unsubscribing?'} name="feedbackRadio" required={true}>
              <Radio.Group
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 8,
                }}
                onChange={handleChangeRadioGroup}
                value={radioGroupValue}
                options={unsubscribeReason}
              />
            </Form.Item>
            <Form.Item label={'What can we improve?'} name="feedbackContent" required={true}>
              <TextArea maxLength={500} placeholder={i18n('feedback.content')} autoSize={{ minRows: 4, maxRows: 8 }} />
            </Form.Item>
            {isDesktop && (
              <Form.Item name="uploadLog" valuePropName="checked">
                <Checkbox>
                  <div className={styles.checkbox}>{i18n('feedback.upload.log')}</div>
                </Checkbox>
              </Form.Item>
            )}
          </Form>
        </div>
      </Modal>
      <Modal
        open={showUnsubscribeModal}
        onCancel={() => setShowUnsubscribeModal(false)}
        onOk={handleUnsubscribeModalOk}
        headerBorder
        okText="Cancel my plan"
        cancelText="Keep my plan"
        width={500}
        destroyOnClose
        title={
          <div className={styles.modalHeaderBox}>
            <div className={styles.modalHeaderTitle}>
              <IconfontSvg code="icon-colourful-crying-face" />
              Cancel your Core plan？
            </div>
            {/* <div className={styles.modalHeaderSubTitle}>
              Please select the reason for canceling your Chat2DB Pro subscription
            </div> */}
          </div>
        }
      >
        <div className={styles.modalContentBox}>
          <div className={styles.modalContentTitle}>You will lose the following features:</div>
          <div className={styles.modalContentList}>
            {lostQualification.map((item) => {
              return (
                <div key={item.value} className={styles.unsubscribeReasonItem}>
                  <IconfontSvg size={18} className={styles.unsubscribeReasonIcon} code="icon-circle-delete" />
                  {item.label}
                </div>
              );
            })}
          </div>
        </div>
      </Modal>
    </div>
  );
});
