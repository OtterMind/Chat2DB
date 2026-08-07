import { memo, useEffect, useRef, useState } from 'react';
import { useStyles } from './style';
import SettingSubsection from '../SettingSubsection';
import i18n, { i18nElement } from '@/i18n';
import { Button, Form, Input } from 'antd';
import { Modal, IconButton } from '@chat2db/ui';
import apiKeysServices, { ApiKeyDetail } from '@/service/apiKeys';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import dayjs from 'dayjs';
import { copyToClipboard } from '@/utils';
import { useGlobalStore } from '@/store/global';
import AntdTable from '@/components/AntdTable';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

// Mask a long-lived API key for list display: prefix + middle dots + last 4.
// The full value is still copied to the clipboard; only the rendered text is masked.
const maskApiKey = (value?: string | null) => {
  if (!value) {
    return '';
  }
  if (value.length <= 8) {
    return '••••';
  }
  return `${value.slice(0, 4)}••••${value.slice(-4)}`;
};

interface IProps {
  className?: string;
}

export default memo<IProps>((props) => {
  const { className } = props;
  const { styles, cx } = useStyles();
  const [visible, setVisible] = useState(false);
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [apiKeys, setApiKeys] = useState<ApiKeyDetail[]>([]);
  const [currentApiKey, setCurrentApiKey] = useState<ApiKeyDetail | null>(null);
  const requestGenerationRef = useRef(0);

  const { openUnifiedConfirmationModal, appUrlConfig } = useGlobalStore((state) => {
    return {
      openUnifiedConfirmationModal: state.openUnifiedConfirmationModal,
      appUrlConfig: state.appUrlConfig,
    };
  });

  useEffect(() => {
    getApiKeyList();
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const renderDescribe = () => {
    return (
      <div>
        {i18nElement(
          'setting.apiKeys.describe',
          <a target="_blank" rel="noreferrer" href={`${appUrlConfig.DOCS_URL}/docs/ai-chat/rest-api`}>
            {i18n('setting.apiKeys.addApiDoc')}
          </a>,
        )}
      </div>
    );
  };

  const getApiKeyList = () => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    apiKeysServices
      .getApiKeyList()
      .then((res) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        setApiKeys(res);
      });
  };

  const createApiKey = () => {
    setVisible(true);
  };

  const handleCreateApiKey = () => {
    form.validateFields().then((values) => {
      setLoading(true);
      apiKeysServices.createApiKey({ name: values.name, nonExpire: 'Y' }).then((res) => {
        setCurrentApiKey(res);
        setLoading(false);
        getApiKeyList();
      });
    });
  };

  useEffect(() => {
    if (!visible) {
      form.resetFields();
      setCurrentApiKey(null);
    }
  }, [visible]);

  const columns: any = [
    {
      title: i18n('common.text.name'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: 'API Key',
      dataIndex: 'apiKey',
      key: 'apiKey',
      render: (data) => {
        // Mask the displayed value (long-lived keys) so it is not persistently
        // visible in the DOM/screen-share; the copy button still copies the full value.
        const masked = maskApiKey(data);
        return (
          <div className={styles.apiKeyBox}>
            <div className={styles.apiKeyText}>{masked}</div>
            <IconButton
              className={styles.iconButton}
              size="md"
              code="icon-copy"
              onClick={() => {
                copyToClipboard(data);
              }}
            />
          </div>
        );
      },
    },
    {
      title: i18nElement('common.text.createTime'),
      dataIndex: 'createTime',
      key: 'createTime',
      render: (data) => {
        return dayjs(data).format('YYYY-MM-DD HH:mm:ss');
      },
    },
    {
      fixed: 'right',
      width: '100px',
      title: i18n('common.text.action'),
      dataIndex: 'delete',
      key: 'createTime',
      render: (data, rowData: ApiKeyDetail) => {
        return (
          <div
            className={styles.deleteButton}
            onClick={() => {
              openUnifiedConfirmationModal({
                title: i18n('common.text.deleteConfirmTitle'),
                content: i18n('setting.apiKeys.deleteTips'),
                onOk: () => {
                  return apiKeysServices.deleteApiKey({ id: rowData.id }).then(() => {
                    apiKeysServices.getApiKeyList().then((res) => {
                      setApiKeys(res);
                    });
                  });
                },
              });
            }}
          >
            {i18n('common.button.delete')}
          </div>
        );
      },
    },
  ];

  return (
    <>
      <div className={cx(styles.apiKeys, className)}>
        <SettingSubsection
          className={styles.apiKeysHeader}
          title={i18n('setting.nav.apiKeys')}
          describe={renderDescribe()}
        />
        <div className={styles.apiKeysBody}>
          <div className={styles.createButton}>
            <div className={styles.titleBox}>
              {i18n('setting.text.APIKeyList')}
              <IconButton code="icon-refresh" size="md" onClick={getApiKeyList} />
            </div>
            <Button type="primary" onClick={createApiKey}>
              {i18n('setting.apiKeys.CreateApiKey')}
            </Button>
          </div>
          <AntdTable className={styles.antdTableBox} dataSource={apiKeys} columns={columns} />
        </div>
      </div>
      <Modal
        open={visible}
        headerIconCode={'icon-apikeys'}
        title={!currentApiKey && i18n('setting.apiKeys.CreateApiKey')}
        onCancel={() => {
          setVisible(false);
        }}
        width={500}
        maskClosable={false}
        footer={
          currentApiKey ? null : (
            <ModalFooterButton
              footerRight={
                <>
                  <Button
                    onClick={() => {
                      setVisible(false);
                    }}
                  >
                    {i18n('common.button.cancel')}
                  </Button>
                  <Button type="primary" loading={loading} onClick={handleCreateApiKey}>
                    {i18n('common.button.confirm')}
                  </Button>
                </>
              }
            />
          )
        }
      >
        <div className={styles.modalBody}>
          {currentApiKey ? (
            <div className={styles.createSuccessTips}>
              <div className={styles.CreateSuccess}>{i18n('setting.apiKeys.CreateSuccess')}</div>
              <div className={styles.createSuccessTips1}>{i18n('setting.apiKeys.createSuccessTips1')}</div>
              <div className={styles.apiKey}>
                {currentApiKey.apiKey}
                <IconButton
                  code="icon-copy"
                  size="md"
                  onClick={() => {
                    copyToClipboard(currentApiKey.apiKey);
                  }}
                />
              </div>
              <div className={styles.createSuccessTips2}>{i18n('setting.apiKeys.createSuccessTips2')}</div>
            </div>
          ) : (
            <Form form={form} autoComplete="off" layout="vertical">
              <Form.Item label={i18n('common.text.name')} name="name">
                <Input />
              </Form.Item>
            </Form>
          )}
        </div>
      </Modal>
    </>
  );
});
