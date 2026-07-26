import { useEffect, useRef, useState } from 'react';
import { Button, Form, Input, Popconfirm, Switch } from 'antd';
import { staticMessage } from '@chat2db/ui';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import { copyToClipboard } from '@/utils/copy';
import SettingSubsection from '../SettingSubsection';
import { useStyles } from '../BaseSetting/style';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

export default function McpSetting() {
  const { styles } = useStyles();
  const [token, setToken] = useState('');
  const requestGenerationRef = useRef(0);
  const { enableMcp, setBaseSetting } = useGlobalStore((state) => {
    return {
      enableMcp: state.baseSetting.enableMcp,
      setBaseSetting: state.setBaseSetting,
    };
  });

  useEffect(() => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    jcefApi.getMcpToken().then((t) => {
      if (isLatestRequest(requestGenerationRef, requestGeneration)) {
        setToken(t);
      }
    });
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  function changeMcpEnabled(checked: boolean) {
    setBaseSetting({ enableMcp: checked });
    staticMessage.info(i18n('setting.text.mcpRestartRequired'));
  }

  function restartApp() {
    jcefApi.restartApp();
  }

  async function copyToken() {
    await copyToClipboard(token);
    staticMessage.success(i18n('common.button.copySuccessfully'));
  }

  function resetToken() {
    jcefApi.resetMcpToken().then((nextToken) => {
      setToken(nextToken);
      staticMessage.success(i18n('setting.text.mcpTokenResetSuccess'));
    });
  }

  return (
    <div className={styles.baseSettingBox}>
      <div>
        <SettingSubsection title={i18n('setting.title.mcp')} describe={i18n('setting.text.mcpDescribe')} />
        <Form className={styles.customFontBox}>
          <Switch checked={!!enableMcp} onChange={changeMcpEnabled} />
          <Button onClick={restartApp}>{i18n('setting.button.restartApp')}</Button>
        </Form>
      </div>
      <div>
        <SettingSubsection title={i18n('setting.title.mcpToken')} describe={i18n('setting.text.mcpTokenDescribe')} />
        <Form className={styles.customFontBox}>
          <Input.Password readOnly value={token} style={{ width: 420 }} />
          <Button onClick={copyToken}>{i18n('common.button.copy')}</Button>
          <Popconfirm
            title={i18n('setting.text.mcpTokenResetConfirm')}
            onConfirm={resetToken}
            okText={i18n('common.button.confirm')}
            cancelText={i18n('common.button.cancel')}
          >
            <Button danger>{i18n('setting.button.resetMcpToken')}</Button>
          </Popconfirm>
        </Form>
      </div>
    </div>
  );
}
