import { useEffect, useState } from 'react';
import configService from '@/service/config';
import { AIType } from '@/typings/ai';
import { Alert, Button, Form, Input, Radio, RadioChangeEvent } from 'antd';
import i18n from '@/i18n';
import { IAiConfig } from '@/typings/setting';
import { AIFormConfig, AITypeName } from './aiTypeConfig';
import { useOrgStore } from '@/store/organization';
import { checkIsOperator } from '@/utils/authPerm';
import { useStyles } from './style';

interface IProps {
  handleApplyAiConfig: (aiConfig: IAiConfig) => void;
  aiConfig: IAiConfig;
}

function capitalizeFirstLetter(string) {
  return string.charAt(0).toUpperCase() + string.slice(1);
}

// openAI
export default function SettingAI(props: IProps) {
  const { styles } = useStyles();
  const curOrg = useOrgStore((s) => s.curOrg);
  const [aiConfig, setAiConfig] = useState<IAiConfig>();

  // have modification permission?
  const noAccess = checkIsOperator(curOrg?.roleCodes) && aiConfig?.aiSqlSource === AIType.CHAT2DBAI;

  useEffect(() => {
    setAiConfig(props.aiConfig);
  }, [props.aiConfig]);

  if (!aiConfig) {
    return <Alert description={i18n('setting.ai.tips')} type="warning" showIcon />;
  }

  const handleAiTypeChange = async (e: RadioChangeEvent) => {
    const aiSqlSource = e.target.value;

    // Query the configuration of the corresponding ai type
    const res = await configService.getAISystemConfig({
      aiSqlSource,
    });
    setAiConfig(res);
  };

  /** application Ai configuration */
  const handleApplyAiConfig = () => {
    const newAiConfig = { ...aiConfig };
    if (newAiConfig.apiHost && !newAiConfig.apiHost?.endsWith('/')) {
      newAiConfig.apiHost = newAiConfig.apiHost + '/';
    }
    if (aiConfig?.aiSqlSource === AIType.CHAT2DBAI) {
      const gatewayBaseUrl = String(window._appGatewayParams?.baseUrl || '/gateway').replace(/\/+$/, '');
      newAiConfig.apiHost = `${gatewayBaseUrl}/model/`;
    }

    if (props.handleApplyAiConfig) {
      props.handleApplyAiConfig(newAiConfig);
    }
  };

  return (
    <>
      <div className={styles.aiSqlSource}>
        <div className={styles.aiSqlSourceTitle}>{i18n('setting.title.aiSource')}:</div>
        <Radio.Group onChange={handleAiTypeChange} value={aiConfig?.aiSqlSource}>
          {Object.keys(AIType).map((key) => (
            <Radio key={key} value={key} style={{ marginBottom: '8px' }}>
              {AITypeName[key]}
            </Radio>
          ))}
        </Radio.Group>
      </div>

      <Form layout="vertical">
        {Object.keys(AIFormConfig[aiConfig?.aiSqlSource]).map((key: string) => (
          <Form.Item
            key={key}
            required={key === 'apiKey' || key === 'secretKey'}
            label={capitalizeFirstLetter(key)}
            className={styles.title}
          >
            <Input
              disabled={noAccess}
              autoComplete="off"
              value={aiConfig[key]}
              placeholder={AIFormConfig[aiConfig?.aiSqlSource]?.[key]?.toString()}
              onChange={(e) => {
                setAiConfig({ ...aiConfig, [key]: e.target.value });
              }}
            />
          </Form.Item>
        ))}
      </Form>

      {aiConfig.aiSqlSource === AIType.RESTAI && (
        <div style={{ margin: '32px 0 ', fontSize: '12px', opacity: '0.5' }}>{`Tips: ${i18n(
          'setting.tab.aiType.custom.tips',
        )}`}</div>
      )}
      {!noAccess && (
        <div className={styles.bottomButton}>
          <Button type="primary" onClick={handleApplyAiConfig}>
            {i18n('setting.button.apply')}
          </Button>
        </div>
      )}
    </>
  );
}
