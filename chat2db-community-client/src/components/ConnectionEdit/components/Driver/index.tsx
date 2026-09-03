import React, { memo, useState, useEffect, useMemo, useRef } from 'react';
import styles from './index.less';
import classnames from 'classnames';
import { i18n } from '@/i18n';
import { Form, Modal, Input, Select } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import connectionService, { IDriverResponse } from '@/service/connection';
import UploadDriver from '@/components/UploadDriver';
import LoadingGracile from '@/components/Loading/LoadingGracile';
import { isCommunityEnv, isDesktop } from '@/utils/env';
import feedback from '@/utils/feedback';
import { canSaveDriverDraft, resolveDriverSavePayload, type IDriverSaveDraft } from './driverUpload';
import { DriverListRequestOwner } from './driverListRequest';
const { Option } = Select;

interface IProps {
  className?: string;
  onChange: (data: any) => void;
  backfillData: any;
  disabled: boolean;
}

enum DownloadStatus {
  Default,
  Loading,
  Error,
  Success,
}

export default memo<IProps>((props) => {
  const { className, backfillData, onChange } = props;
  const [downloadStatus, setDownloadStatus] = useState<DownloadStatus>(DownloadStatus.Default);
  const [driverForm] = Form.useForm();
  const [driverObj, setDriverObj] = useState<IDriverResponse>();
  const [uploadDriverModal, setUploadDriverModal] = useState(false);
  const [driverSaved, setDriverSaved] = useState<IDriverSaveDraft>({ dbType: backfillData?.type });
  const [desktopLoading, setDesktopLoading] = useState(false);
  const driverListRequestOwnerRef = useRef<DriverListRequestOwner>();
  if (!driverListRequestOwnerRef.current) {
    driverListRequestOwnerRef.current = new DriverListRequestOwner();
  }
  const driverListRequestOwner = driverListRequestOwnerRef.current;
  const driverListScope = useMemo(
    () => driverListRequestOwner.createScope(),
    [
      backfillData?.id,
      backfillData?.type,
      backfillData?.driverConfig?.jdbcDriver,
      backfillData?.driverConfig?.jdbcDriverClass,
    ],
  );

  useEffect(() => {
    const dbType = backfillData?.type;
    driverListRequestOwner.activate(driverListScope);
    if (backfillData) {
      void getDriverList(driverListScope, dbType);
    }
    return () => driverListRequestOwner.dispose(driverListScope);
  }, [driverListScope]);

  useEffect(() => {
    if (backfillData) {
      const data = {
        jdbcDriverClass: backfillData?.driverConfig?.jdbcDriverClass,
        jdbcDriver: backfillData?.driverConfig?.jdbcDriver,
      };
      driverForm.setFieldsValue(data);
      onChange(data);
    }
  }, [backfillData?.driverConfig, backfillData?.id]);

  function getDriverList(expectedScope = driverListScope, expectedDbType = backfillData.type) {
    return driverListRequestOwner.run(
      expectedScope,
      () => connectionService.getDriverList({ dbType: expectedDbType }),
      {
        onSuccess: (res) => {
          if (!res) {
            return;
          }
          setDriverObj({
            ...res,
            driverConfigList: res.driverConfigList || [],
          });
          if (res.driverConfigList?.length && !backfillData?.driverConfig?.jdbcDriver) {
            const data = {
              jdbcDriverClass: res.driverConfigList[0]?.jdbcDriverClass,
              jdbcDriver: res.driverConfigList[0]?.jdbcDriver,
            };
            driverForm.setFieldsValue(data);
            onChange(data);
          }
        },
        onError: (error) => console.error('get driver list error', error),
      },
    );
  }

  function formChange(data: any) {
    setDriverSaved(data);
  }

  async function saveDriver() {
    try {
      setDesktopLoading(true);
      const savePayload = await resolveDriverSavePayload(driverSaved, isDesktop, (file) =>
        connectionService.uploadDriver({ file }),
      );
      await connectionService.saveDriver(savePayload);
      setDesktopLoading(false);
      setUploadDriverModal(false);
      getDriverList();
    } catch {
      setDesktopLoading(false);
    }
  }

  function downloadDrive() {
    setDownloadStatus(DownloadStatus.Loading);
    connectionService
      .downloadDriver({ dbType: backfillData.type })
      .then(() => {
        setDownloadStatus(DownloadStatus.Success);
        getDriverList();
      })
      .catch(() => {
        console.error('download driver error');
        setDownloadStatus(DownloadStatus.Error);
      });
  }

  function handleDeleteDriver(e: React.MouseEvent, jdbcDriver: string) {
    e.stopPropagation();
    e.preventDefault();
    Modal.confirm({
      title: i18n('connection.title.deleteDriver'),
      content: i18n('connection.tips.deleteDriverConfirm', jdbcDriver),
      onOk: async () => {
        try {
          await connectionService.deleteDriver({
            dbType: backfillData.type,
            jdbcDriver: [jdbcDriver],
          });
          feedback.success(i18n('common.text.successfullyDelete'));
          if (driverForm.getFieldValue('jdbcDriver') === jdbcDriver) {
            driverForm.setFieldsValue({ jdbcDriver: undefined, jdbcDriverClass: undefined });
            onChange({ jdbcDriver: undefined, jdbcDriverClass: undefined });
          }
          getDriverList();
        } catch (error) {
          console.error('delete driver error', error);
        }
      },
    });
  }

  function onValuesChange(data: any) {
    const selected = driverObj?.driverConfigList.find((t) => t.jdbcDriver === data.jdbcDriver);
    driverForm.setFieldsValue({
      jdbcDriverClass: selected?.jdbcDriverClass,
    });
    onChange({
      jdbcDriverClass: selected?.jdbcDriverClass,
      jdbcDriver: data.jdbcDriver,
    });
  }

  return (
    <div className={classnames(styles.box, className)}>
      <Form disabled={props.disabled} form={driverForm} onValuesChange={onValuesChange} colon={false}>
        <Form.Item labelAlign="left" name="jdbcDriver" label={i18n('connection.title.driver')}>
          <Select optionLabelProp="label">
            {driverObj?.driverConfigList?.map((t) => (
              <Option key={t.jdbcDriver} value={t.jdbcDriver} label={t.jdbcDriver}>
                <div className={styles.driverOption}>
                  <span className={styles.driverOptionName}>{t.jdbcDriver}</span>
                  {t.custom && (
                    <DeleteOutlined
                      className={styles.driverOptionDelete}
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={(e) => handleDeleteDriver(e, t.jdbcDriver)}
                    />
                  )}
                </div>
              </Option>
            ))}
          </Select>
        </Form.Item>
        <Form.Item labelAlign="left" name="jdbcDriverClass" label="Class">
          <Input disabled />
        </Form.Item>
      </Form>
      <div className={styles.downloadDriveFooter}>
        {(driverObj?.driverConfigList && !driverObj?.driverConfigList?.length) ||
        downloadStatus === DownloadStatus.Success ? (
          <div onClick={downloadDrive} className={styles.downloadDrive}>
            {downloadStatus === DownloadStatus.Default && (
              <div className={classnames(styles.downloadText, styles.downloadTextDownload)}>
                {i18n('connection.text.downloadDriver')}
              </div>
            )}
            {downloadStatus === DownloadStatus.Loading && (
              <div className={classnames(styles.downloadText, styles.downloadTextLoading)}>
                <LoadingGracile />
                <div className={styles.text}>{i18n('connection.text.downloading')}</div>
              </div>
            )}
            {downloadStatus === DownloadStatus.Error && (
              <div className={classnames(styles.downloadText, styles.downloadTextError)}>
                {i18n('connection.text.tryAgainDownload')}
              </div>
            )}
            {downloadStatus === DownloadStatus.Success && (
              <div className={classnames(styles.downloadText, styles.downloadTextSuccess)}>
                {i18n('connection.text.downloadSuccess')}
              </div>
            )}
          </div>
        ) : (
          <div />
        )}
        {(isDesktop || isCommunityEnv) && (
          <div
            className={styles.uploadCustomDrive}
            onClick={() => {
              setUploadDriverModal(true);
            }}
          >
            {i18n('connection.tips.customUpload')}
          </div>
        )}
      </div>
      <Modal
        destroyOnClose={true}
        title={i18n('connection.title.uploadDriver')}
        open={uploadDriverModal}
        onOk={() => {
          saveDriver();
        }}
        maskClosable={false}
        onCancel={() => {
          setUploadDriverModal(false);
        }}
        confirmLoading={desktopLoading}
        okButtonProps={{ disabled: !canSaveDriverDraft(driverSaved) }}
      >
        <UploadDriver
          jdbcDriverClass={driverObj?.defaultDriverConfig?.jdbcDriverClass}
          formChange={formChange}
          databaseType={backfillData.type}
        />
      </Modal>
    </div>
  );
});
