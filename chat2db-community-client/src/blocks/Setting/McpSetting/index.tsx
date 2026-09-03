import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { Alert, Button, Form, Input, Popconfirm, Switch } from 'antd';
import { staticMessage } from '@chat2db/ui';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import type { McpStatus } from '@/typings/settings';
import { copyToClipboard } from '@/utils/copy';
import feedback from '@/utils/feedback';
import SettingSubsection from '../SettingSubsection';
import { useStyles } from '../BaseSetting/style';
import {
  canStartMcpOperation,
  createMcpOperationId,
  getErrorMessage,
  initialMcpLifecycleState,
  reduceMcpLifecycleState,
  type McpOperation,
} from './mcpLifecycle';
import { useMcpStyles } from './style';
import { McpTokenRequestCoordinator } from './mcpTokenRequestCoordinator';

export default function McpSetting() {
  const { styles } = useStyles();
  const { styles: mcpStyles } = useMcpStyles();
  const [token, setToken] = useState('');
  const [resetTokenLoading, setResetTokenLoading] = useState(false);
  const [lifecycle, dispatch] = useReducer(reduceMcpLifecycleState, initialMcpLifecycleState);
  const activeOperationIdRef = useRef<string | null>(null);
  const tokenRequestCoordinatorRef = useRef(new McpTokenRequestCoordinator());
  const setBaseSetting = useGlobalStore((state) => state.setBaseSetting);

  const startOperation = useCallback((operation: McpOperation) => {
    if (!canStartMcpOperation(activeOperationIdRef.current)) {
      return null;
    }
    const operationId = createMcpOperationId();
    activeOperationIdRef.current = operationId;
    dispatch({ type: 'START', operation, operationId });
    return operationId;
  }, []);

  const applyStatus = useCallback(
    (status: McpStatus) => {
      if (activeOperationIdRef.current !== status.operationId) {
        return false;
      }
      activeOperationIdRef.current = null;
      dispatch({ type: 'STATUS', status });
      setBaseSetting({ enableMcp: status.configuredEnabled });
      return true;
    },
    [setBaseSetting],
  );

  const failOperation = useCallback((operationId: string, error: unknown) => {
    if (activeOperationIdRef.current !== operationId) {
      return false;
    }
    activeOperationIdRef.current = null;
    dispatch({ type: 'FAILURE', operationId, error: getErrorMessage(error) });
    return true;
  }, []);

  useEffect(() => {
    const tokenOwner = tokenRequestCoordinatorRef.current.beginMount();
    const operationId = startOperation('loading');
    if (!operationId) {
      return () => tokenRequestCoordinatorRef.current.invalidate();
    }
    jcefApi.getMcpStatus({ operationId })
      .then(applyStatus)
      .catch((error) => {
        failOperation(operationId, error);
      });
    jcefApi.getMcpToken()
      .then((t) => {
        if (tokenRequestCoordinatorRef.current.isCurrent(tokenOwner)) {
          setToken(t);
        }
      })
      .catch(() => {
        if (tokenRequestCoordinatorRef.current.isCurrent(tokenOwner)) {
          feedback.error(i18n('setting.mcp.tokenLoadFailed'));
        }
      });
    return () => {
      tokenRequestCoordinatorRef.current.invalidate();
      if (activeOperationIdRef.current === operationId) {
        activeOperationIdRef.current = null;
      }
    };
  }, [applyStatus, failOperation, startOperation]);

  async function changeMcpEnabled(checked: boolean) {
    const operationId = startOperation('saving');
    if (!operationId) {
      return;
    }
    try {
      const status = await jcefApi.setMcpEnabled({ operationId, enabled: checked });
      if (applyStatus(status) && status.restartRequired) {
        staticMessage.info(i18n('setting.text.mcpRestartRequired'));
      }
    } catch (error) {
      failOperation(operationId, error);
    }
  }

  async function restartApp() {
    const operationId = startOperation('restarting');
    if (!operationId) {
      return;
    }
    try {
      const result = await jcefApi.restartApp({ operationId });
      if (activeOperationIdRef.current !== operationId || result.operationId !== operationId) {
        return;
      }
      if (!result.accepted) {
        failOperation(operationId, i18n('setting.mcp.restartAlreadyInProgress'));
        return;
      }
    } catch (error) {
      failOperation(operationId, error);
    }
  }

  async function copyToken() {
    await copyToClipboard(token);
    feedback.success(i18n('common.button.copySuccessfully'));
  }

  async function resetToken() {
    const owner = tokenRequestCoordinatorRef.current.beginReset();
    if (!owner) {
      return;
    }
    setResetTokenLoading(true);
    try {
      const nextToken = await jcefApi.resetMcpToken();
      if (!tokenRequestCoordinatorRef.current.isCurrent(owner)) {
        return;
      }
      setToken(nextToken);
      feedback.success(i18n('setting.text.mcpTokenResetSuccess'));
    } catch {
      if (tokenRequestCoordinatorRef.current.isCurrent(owner)) {
        feedback.error(i18n('setting.mcp.operationFailed'));
      }
    } finally {
      if (tokenRequestCoordinatorRef.current.finishReset(owner)) {
        setResetTokenLoading(false);
      }
    }
  }

  const status = lifecycle.status;
  const controlsBusy = lifecycle.pendingOperation !== null;
  const canRestart = !!status && (
    status.restartRequired || status.runtimeState === 'FAILED' || status.runtimeState === 'UNKNOWN'
  );

  return (
    <div className={styles.baseSettingBox}>
      <div data-setting-search-id="mcp.service">
        <div className={mcpStyles.serviceControls}>
          <Form className={mcpStyles.actionRow}>
            <Switch
              aria-label={i18n('setting.title.mcp')}
              checked={status?.configuredEnabled ?? false}
              disabled={controlsBusy}
              loading={lifecycle.pendingOperation === 'loading' || lifecycle.pendingOperation === 'saving'}
              onChange={changeMcpEnabled}
            />
            <Button
              disabled={controlsBusy || !canRestart}
              loading={lifecycle.pendingOperation === 'restarting'}
              onClick={restartApp}
            >
              {i18n('setting.button.restartApp')}
            </Button>
          </Form>
          {lifecycle.error && (
            <Alert showIcon type="error" message={i18n('setting.mcp.operationFailed')} description={lifecycle.error} />
          )}
        </div>
      </div>
      <div data-setting-search-id="mcp.token">
        <SettingSubsection
          title={<span data-setting-search-title="true">{i18n('setting.title.mcpToken')}</span>}
          describe={i18n('setting.text.mcpTokenDescribe')}
        />
        <Form className={styles.customFontBox}>
          <Input.Password readOnly value={token} style={{ flex: '1 1 280px', maxWidth: 420, minWidth: 0 }} />
          <Button onClick={copyToken}>{i18n('common.button.copy')}</Button>
          <Popconfirm
            title={i18n('setting.text.mcpTokenResetConfirm')}
            onConfirm={resetToken}
            okText={i18n('common.button.confirm')}
            cancelText={i18n('common.button.cancel')}
          >
            <Button
              danger
              disabled={resetTokenLoading || lifecycle.pendingOperation === 'restarting'}
              loading={resetTokenLoading}
            >
              {i18n('setting.button.resetMcpToken')}
            </Button>
          </Popconfirm>
        </Form>
      </div>
    </div>
  );
}
