import { useEffect, useRef, useState } from 'react';
import { Button, Form, Input, InputNumber, Radio, Select, Tooltip } from 'antd';
import { CircleHelp } from 'lucide-react';
import { staticMessage } from '@chat2db/ui';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import networkProxyService, { INetworkProxySettings, NetworkProxyMode, NetworkProxyType } from '@/service/networkProxy';
import { useStyles as useBaseStyles } from '../BaseSetting/style';
import { useStyles } from './style';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';
import SearchTargetLabel from '../SearchTargetLabel';

const defaultProxySettings: INetworkProxySettings = {
  mode: NetworkProxyMode.NO_PROXY,
  proxyType: NetworkProxyType.HTTP,
  host: '',
  port: undefined,
  noProxyHosts: 'localhost|127.*|[::1]',
};

export default function NetworkProxySetting() {
  const { styles: baseStyles } = useBaseStyles();
  const { styles } = useStyles();
  const [form] = Form.useForm<INetworkProxySettings & { testUrl?: string }>();
  const [loading, setLoading] = useState(false);
  const [testing, setTesting] = useState(false);
  const [restartRequired, setRestartRequired] = useState(false);
  const mode = Form.useWatch('mode', form);
  const requestGenerationRef = useRef(0);

  useEffect(() => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    networkProxyService.get().then((settings) => {
      if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
      const nextSettings = {
        ...defaultProxySettings,
        ...settings,
      };
      form.setFieldsValue({
        ...nextSettings,
        testUrl: 'https://www.google.com',
      });
      setRestartRequired(!!nextSettings.restartRequired);
    });
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, [form]);

  async function saveSettings() {
    const values = await form.validateFields();
    setLoading(true);
    try {
      const nextSettings = await networkProxyService.save(toProxySettings(values));
      form.setFieldsValue(nextSettings);
      setRestartRequired(!!nextSettings.restartRequired);
      staticMessage.success(i18n('common.text.submittedSuccessfully'));
    } finally {
      setLoading(false);
    }
  }

  async function testConnection() {
    const values = await form.validateFields();
    setTesting(true);
    try {
      await networkProxyService.test({
        settings: toProxySettings(values),
        testUrl: values.testUrl,
      });
      staticMessage.success(i18n('setting.networkProxy.testSuccess'));
    } finally {
      setTesting(false);
    }
  }

  function restartApp() {
    jcefApi.restartApp();
  }

  return (
    <div className={baseStyles.baseSettingBox}>
      <div>
        <Form
          form={form}
          className={styles.form}
          layout="vertical"
          initialValues={{
            ...defaultProxySettings,
            testUrl: 'https://www.google.com',
          }}
        >
          <Form.Item
            name="mode"
            label={
              <SearchTargetLabel className={styles.titleWithHelp} targetId="networkProxy.mode">
                {i18n('setting.networkProxy.mode')}
                <HelpTooltip title={i18n('setting.networkProxy.modeTip')} />
              </SearchTargetLabel>
            }
            className={styles.modeItem}
          >
            <Radio.Group className={styles.modeGroup}>
              <ModeRadio
                value={NetworkProxyMode.NO_PROXY}
                label={i18n('setting.networkProxy.noProxy')}
                tip={i18n('setting.networkProxy.noProxyTip')}
              />
              <ModeRadio
                value={NetworkProxyMode.SYSTEM}
                label={i18n('setting.networkProxy.systemProxy')}
                tip={i18n('setting.networkProxy.systemProxyTip')}
              />
              <ModeRadio
                value={NetworkProxyMode.MANUAL}
                label={i18n('setting.networkProxy.manualProxy')}
                tip={i18n('setting.networkProxy.manualProxyTip')}
              />
            </Radio.Group>
          </Form.Item>

          {mode === NetworkProxyMode.MANUAL && (
            <>
              <Form.Item name="proxyType" label={i18n('setting.networkProxy.type')}>
                <Select
                  options={[
                    { label: 'HTTP', value: NetworkProxyType.HTTP },
                    { label: 'SOCKS', value: NetworkProxyType.SOCKS },
                  ]}
                />
              </Form.Item>
              <div className={styles.row}>
                <Form.Item
                  name="host"
                  label={i18n('setting.networkProxy.host')}
                  rules={[{ required: true, message: i18n('setting.networkProxy.hostRequired') }]}
                >
                  <Input placeholder="127.0.0.1" autoComplete="off" />
                </Form.Item>
                <Form.Item
                  name="port"
                  label={i18n('setting.networkProxy.port')}
                  rules={[{ required: true, message: i18n('setting.networkProxy.portRequired') }]}
                >
                  <InputNumber min={1} max={65535} precision={0} style={{ width: '100%' }} placeholder="7897" />
                </Form.Item>
              </div>
              <Form.Item name="noProxyHosts" label={i18n('setting.networkProxy.noProxyHosts')}>
                <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} placeholder="localhost|127.*|[::1]" />
              </Form.Item>
            </>
          )}

          <Form.Item
            name="testUrl"
            label={
              <SearchTargetLabel targetId="networkProxy.test">{i18n('setting.networkProxy.testUrl')}</SearchTargetLabel>
            }
          >
            <Input placeholder="https://www.google.com" autoComplete="off" />
          </Form.Item>

          <div className={styles.actions}>
            <Button type="primary" loading={loading} onClick={saveSettings}>
              {i18n('setting.button.apply')}
            </Button>
            <Button loading={testing} onClick={testConnection}>
              {i18n('setting.networkProxy.testConnection')}
            </Button>
            {restartRequired && (
              <>
                <span className={styles.restartTip}>{i18n('setting.networkProxy.restartRequired')}</span>
                <Button onClick={restartApp}>{i18n('setting.button.restartApp')}</Button>
              </>
            )}
          </div>
        </Form>
      </div>
    </div>
  );
}

function ModeRadio({ value, label, tip }: { value: NetworkProxyMode; label: string; tip: string }) {
  const { styles } = useStyles();

  return (
    <Radio value={value}>
      <span className={styles.titleWithHelp}>
        <span>{label}</span>
        <HelpTooltip title={tip} />
      </span>
    </Radio>
  );
}

function HelpTooltip({ title }: { title: string }) {
  const { styles } = useStyles();

  return (
    <Tooltip title={title} mouseEnterDelay={0.2}>
      <CircleHelp className={styles.helpIcon} size={14} />
    </Tooltip>
  );
}

function toProxySettings(values: INetworkProxySettings & { testUrl?: string }): INetworkProxySettings {
  return {
    mode: values.mode,
    proxyType: values.proxyType,
    host: values.host?.trim(),
    port: values.port,
    noProxyHosts: values.noProxyHosts,
  };
}
