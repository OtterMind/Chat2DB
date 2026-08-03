import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, List, Modal, Radio, Space, Tag } from 'antd';
import i18n from '@/i18n';
import type { AiSecretImportAttemptView, AiSecretImportItemResult } from '@/typings/aiSubscription';
import {
  listVisibleMigrationItems,
  summarizeMigrationProgress,
} from '../migrationPlan';

interface MigrationWizardModalProps {
  open: boolean;
  attempt: AiSecretImportAttemptView | null;
  backendHasValidDefault: boolean;
  backendDefaultModelRefKey?: string | null;
  results?: AiSecretImportItemResult[];
  onStart: () => void;
  onConfirm: (selectedDefaultItemId: string | null | undefined) => void;
  onClose: () => void;
  onRetryFailed?: () => void;
}

export default function MigrationWizardModal({
  open,
  attempt,
  backendHasValidDefault,
  backendDefaultModelRefKey,
  results = [],
  onStart,
  onConfirm,
  onClose,
  onRetryFailed,
}: MigrationWizardModalProps) {
  const [defaultChoice, setDefaultChoice] = useState<string | null | undefined>();

  useEffect(() => {
    setDefaultChoice(backendHasValidDefault ? null : undefined);
  }, [attempt?.attemptId, backendHasValidDefault]);

  const progress = useMemo(
    () => (attempt ? summarizeMigrationProgress(attempt, results) : null),
    [attempt, results],
  );

  const items = attempt ? listVisibleMigrationItems(attempt.items) : [];

  return (
    <Modal
      open={open}
      title={i18n('ai.subscription.migration.title')}
      onCancel={onClose}
      footer={null}
      destroyOnClose
      width={560}
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <div>{i18n('ai.subscription.migration.desc')}</div>
        {!attempt ? (
          <Space>
            <Button type="primary" onClick={onStart}>
              {i18n('ai.subscription.migration.start')}
            </Button>
            <Button onClick={onClose}>{i18n('ai.subscription.migration.later')}</Button>
          </Space>
        ) : (
          <>
            <List
              size="small"
              dataSource={items}
              renderItem={(item) => (
                <List.Item>
                  <List.Item.Meta title={item.configName} description={item.provider} />
                  <Tag>
                    {i18n(`ai.subscription.migration.itemStatus.${item.status.toLowerCase()}` as any)}
                  </Tag>
                </List.Item>
              )}
            />
            <div>
              <div style={{ marginBottom: 8 }}>{i18n('ai.subscription.migration.defaultTitle')}</div>
              <Radio.Group
                value={defaultChoice === null ? '__skip__' : defaultChoice}
                onChange={(event) => setDefaultChoice(event.target.value === '__skip__' ? null : event.target.value)}
                disabled={backendHasValidDefault}
              >
                <Space direction="vertical">
                  {backendHasValidDefault ? (
                    <Radio value="__skip__">
                      {i18n('ai.subscription.migration.defaultKeepBackend')}
                      {backendDefaultModelRefKey ? ` (${backendDefaultModelRefKey})` : ''}
                    </Radio>
                  ) : (
                    <>
                      {items.map((item) => (
                        <Radio key={item.itemId} value={item.itemId}>
                          {item.configName} ({item.provider})
                        </Radio>
                      ))}
                      <Radio value="__skip__">{i18n('ai.subscription.migration.defaultSkip')}</Radio>
                    </>
                  )}
                </Space>
              </Radio.Group>
              {!backendHasValidDefault && defaultChoice === undefined ? (
                <Alert
                  style={{ marginTop: 8 }}
                  type="info"
                  showIcon
                  message={i18n('ai.subscription.migration.defaultChoose')}
                />
              ) : null}
            </div>
            {progress && progress.pendingCount > 0 ? (
              <Button
                type="primary"
                disabled={!backendHasValidDefault && defaultChoice === undefined}
                onClick={() => onConfirm(defaultChoice)}
              >
                {i18n('ai.subscription.migration.confirm')}
              </Button>
            ) : null}
            {progress && progress.failedCount > 0 ? (
              <Button onClick={onRetryFailed}>{i18n('ai.subscription.migration.retryFailed')}</Button>
            ) : null}
          </>
        )}
      </Space>
    </Modal>
  );
}
