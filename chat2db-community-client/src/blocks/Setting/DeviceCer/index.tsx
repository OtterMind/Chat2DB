import { useEffect, useRef, useState } from 'react';
import { useStyles } from './style';
import SettingSubsection from '../SettingSubsection';
import { Form, Input, Select, Button, Typography, Flex, Popconfirm } from 'antd';
import LicenseService from '@/service/license';
import { ILicenseVO } from '@/typings/license';
import { formatDate } from '@/utils/date';
import { Modal, staticMessage, TextArea } from '@chat2db/ui';
import i18n from '@/i18n';
import { copyToClipboard } from '@/utils';
import { Dot } from 'lucide-react';
import { useGlobalStore } from '@/store/global';
import { openWebPage } from '@/utils/url';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

const DeviceCer = () => {
  const { styles } = useStyles();
  const [form] = Form.useForm();
  const [licenseList, setLicenseList] = useState<ILicenseVO[]>([]);
  const [disabledSubmitButton, setDisabledSubmitButton] = useState(true);
  const [isLoading, setIsLoading] = useState(false);
  const [openModal, setOpenModal] = useState(false);
  const [cer, setCer] = useState('');
  const { appUrlConfig } = useGlobalStore((state) => ({
    appUrlConfig: state.appUrlConfig,
  }));
  const requestGenerationRef = useRef(0);

  useEffect(() => {
    queryLicenseInfo();
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const handleFormValuesChange = (changedValues: any, allValues: any) => {
    handleDisabledSubmitButton(allValues);
  };

  const handleDisabledSubmitButton = (values: any) => {
    const { licenseId, deviceName, deviceType, deviceId } = values;

    if (!licenseId || !deviceName || !deviceType || !deviceId || deviceId.length < 96) {
      setDisabledSubmitButton(true);
      return;
    }

    setDisabledSubmitButton(false);
  };

  const queryLicenseInfo = async () => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    const res = await LicenseService.getLicenseList();
    const filterArr = res.filter((t) => t.canGenerateCer);
    const canSelectArr = filterArr.filter((t) => t.licenseBindCount < t.licenseAvailableCount);
    const canNotSelectArr = filterArr.filter((t) => t.licenseBindCount >= t.licenseAvailableCount);
    if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
    setLicenseList([...canSelectArr, ...canNotSelectArr]);
  };

  const handleSubmit = async () => {
    setIsLoading(true);
    try {
      const values = form.getFieldsValue();
      const res = await LicenseService.generateCertificate(values);
      if (res) {
        staticMessage.success(i18n('license.getCertificateSuccess'));
        setCer(res);
        setOpenModal(true);
      }
    } catch {
      staticMessage.error(i18n('license.getCertificateError'));
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className={styles.wrapper}>
      <SettingSubsection
        title={i18n('license.getDeviceCertificateTitle')}
        describe={
          <div>
            <span>{i18n('license.certificateDescription')}</span>
            <span className={styles.warning}>{i18n('license.certificateWarning')}</span>
          </div>
        }
      />

      <Form form={form} layout="vertical" style={{ maxWidth: 600 }} onValuesChange={handleFormValuesChange}>
        <Form.Item label="License ID" name="licenseId" tooltip={i18n('license.licenseTooltip')}>
          <Select
            placeholder={i18n('license.selectOrder')}
            options={licenseList.map((t) => ({
              label: (
                <Flex justify="space-between">
                  <div>{t.license}</div>
                  <div className={styles.optionTime}>{formatDate(t.createTime)}</div>
                </Flex>
              ),
              value: t.id.toString(),
              disabled: t.licenseBindCount >= t.licenseAvailableCount,
              ...t,
            }))}
            optionRender={(option) => (
              <Flex vertical style={{ padding: 6 }}>
                <Flex justify="space-between" align="center">
                  <div>
                    <span>{i18n('license.permanentVersion')}</span> ({option.data.licenseBindCount}/
                    {option.data.licenseAvailableCount})
                  </div>
                  <div className={styles.optionTime}>{formatDate(option.data.createTime)}</div>
                </Flex>
                <div className={styles.optionLicense}>{option.data.license}</div>
              </Flex>
            )}
          />
        </Form.Item>

        <Form.Item
          label={i18n('license.deviceNameLabel')}
          name="deviceName"
          tooltip={i18n('license.deviceNameTooltip')}
        >
          <Input placeholder={i18n('license.deviceNamePlaceholder')} maxLength={100} autoComplete="off" />
        </Form.Item>

        <Form.Item label={i18n('license.osLabel')} name="deviceType">
          <Select
            placeholder={i18n('license.selectOS')}
            options={['Windows', 'Linux', 'MacOS'].map((t) => ({ label: t, value: t }))}
          />
        </Form.Item>

        <Form.Item label={i18n('license.deviceId')} name="deviceId" extra={i18n('license.deviceIdExtra')}>
          <Input placeholder={i18n('license.deviceIdPlaceholder')} autoComplete="off" />
        </Form.Item>

        <Flex gap={12} align="center" style={{ marginBottom: 12 }}>
          <Popconfirm title={i18n('license.offlineAIConfirm')} onConfirm={handleSubmit}>
            <Button loading={isLoading} type="primary" disabled={disabledSubmitButton}>
              {i18n('license.getCertificate')}
            </Button>
          </Popconfirm>
        </Flex>

        <Typography.Text type="secondary">{i18n('license.certificateNote')}</Typography.Text>
      </Form>

      <Modal
        open={openModal}
        title={i18n('license.modalTitle')}
        titleDesc={i18n('license.modalTitleDesc')}
        footer={null}
        headerBorder={true}
        centered
        destroyOnClose
        width={500}
        onClose={() => setOpenModal(false)}
        onCancel={() => setOpenModal(false)}
      >
        <div className={styles.modalWrapper}>
          <Flex vertical gap={12}>
            <div className={styles.modalTitle}>{i18n('license.deviceCertificateTitle')}</div>
            <TextArea disabled value={cer} autoSize={{ minRows: 8, maxRows: 8 }} />

            <Button type="primary" onClick={() => copyToClipboard(cer)}>
              {i18n('license.copyButton')}
            </Button>
          </Flex>

          <Flex vertical gap={12}>
            <div className={styles.modalTitle}>{i18n('license.offlineUsageNotes')}</div>
            <Flex vertical className={styles.modalTips}>
              <Flex align="center">
                <Dot size={20} className={styles.modalBulletPoint} />
                <div className={styles.warning}>{i18n('license.offlineActivationAIWarning')}</div>
              </Flex>
              <Flex align="center">
                <Dot size={20} className={styles.modalBulletPoint} />
                <div className={styles.warning}>{i18n('license.offlineAIWarning')}</div>
              </Flex>
              <Flex align="center">
                <Dot size={20} className={styles.modalBulletPoint} />
                <div>{i18n('setting.license.deviceLimit')}</div>
              </Flex>
              <Flex align="center">
                <Dot size={20} className={styles.modalBulletPoint} />
                <Flex align="center" wrap="wrap">
                  {i18n('license.offlineActivationIntro')}
                  <div
                    className={styles.link}
                    onClick={() => {
                      openWebPage(`${appUrlConfig.DOCS_URL}/docs/start-guide/offline-activate`);
                    }}
                  >
                    {i18n('license.viewActivationProcess')}
                  </div>
                </Flex>
              </Flex>
              <Flex align="center">
                <Dot size={20} className={styles.modalBulletPoint} />
                <div>{i18n('setting.license.contactInfo')}</div>
              </Flex>
            </Flex>
          </Flex>
        </div>
      </Modal>
    </div>
  );
};

export default DeviceCer;
