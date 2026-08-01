import { useEffect, useState } from 'react';
import { Alert, Button, Spin } from 'antd';
import i18n from '@/i18n';
import type { AiProviderId } from '@/typings/aiSubscription';
import { useSubscriptionAiStore } from '@/store/aiSubscription';
import { listManageableProviders, presentAccountState } from '../accountState';
import { subscriptionRuntimeErrorI18nKey } from '../capability';
import MigrationWizardModal from '../MigrationWizardModal';
import { useStyles } from './style';

/**
 * Settings entry for eligible subscription providers on packaged Community JCEF.
 * SuperGrok / non-eligible providers are omitted. API-key models are preserved separately.
 */
export default function SubscriptionAccountPanel() {
  const { styles } = useStyles();
  const [migrationOpen, setMigrationOpen] = useState(false);
  const {
    hydrated,
    surfaceAvailable,
    capability,
    loading,
    providers,
    preferences,
    migrationAttempt,
    migrationResults,
    lastErrorCode,
    refreshSurface,
    refreshProvidersAndModels,
    startConnect,
    cancelConnect,
    disconnect,
    retryDisconnect,
    retryDiscovery,
    beginMigration,
    confirmMigration,
    clearMigration,
  } = useSubscriptionAiStore((state) => ({
    hydrated: state.hydrated,
    surfaceAvailable: state.surfaceAvailable,
    capability: state.capability,
    loading: state.loading,
    providers: state.providers,
    preferences: state.preferences,
    migrationAttempt: state.migrationAttempt,
    migrationResults: state.migrationResults,
    lastErrorCode: state.lastErrorCode,
    refreshSurface: state.refreshSurface,
    refreshProvidersAndModels: state.refreshProvidersAndModels,
    startConnect: state.startConnect,
    cancelConnect: state.cancelConnect,
    disconnect: state.disconnect,
    retryDisconnect: state.retryDisconnect,
    retryDiscovery: state.retryDiscovery,
    beginMigration: state.beginMigration,
    confirmMigration: state.confirmMigration,
    clearMigration: state.clearMigration,
  }));

  useEffect(() => {
    void refreshSurface();
  }, [refreshSurface]);

  if (!hydrated) {
    return <Spin />;
  }

  const manageable = listManageableProviders(providers);
  const runtimeErrorCode =
    lastErrorCode || (capability && !capability.enabled ? capability.disabledReason : null);
  const errorI18nKey = subscriptionRuntimeErrorI18nKey(runtimeErrorCode);
  const retry = () => {
    if (surfaceAvailable && lastErrorCode === 'PROVIDER_FETCH_FAILED') {
      void refreshProvidersAndModels();
      return;
    }
    void refreshSurface();
  };

  return (
    <div className={styles.root}>
      <div className={styles.onboarding}>
        <div className={styles.onboardingTitle}>{i18n('ai.subscription.onboarding.title')}</div>
        <ol className={styles.steps}>
          <li>{i18n('ai.subscription.onboarding.stepConnect')}</li>
          <li>{i18n('ai.subscription.onboarding.stepAuthorize')}</li>
          <li>{i18n('ai.subscription.onboarding.stepChooseModel')}</li>
        </ol>
      </div>
      {errorI18nKey ? (
        <Alert
          type="error"
          showIcon
          message={i18n('ai.subscription.error.title')}
          description={i18n(errorI18nKey as any)}
          action={
            <Button size="small" onClick={retry} loading={loading}>
              {i18n('ai.subscription.error.retry')}
            </Button>
          }
        />
      ) : null}
      {!surfaceAvailable && !errorI18nKey ? (
        <Alert type="info" showIcon message={i18n('ai.subscription.surface.unavailable')} />
      ) : null}
      <div className={styles.hint}>{i18n('ai.subscription.account.apiKeyPreserved')}</div>
      {!surfaceAvailable ? null : manageable.length === 0 ? (
        <Alert type="info" showIcon message={i18n('ai.subscription.account.notEligible')} />
      ) : (
        manageable.map((connection) => {
          const presentation = presentAccountState(connection);
          const provider = connection.provider as AiProviderId;
          return (
            <div key={provider} className={styles.card} data-provider={provider}>
              <div className={styles.header}>
                <div className={styles.title}>{connection.displayName}</div>
                <div className={styles.status}>{i18n(presentation.statusI18nKey as any)}</div>
              </div>
              {connection.maskedAccount ? (
                <div className={styles.account}>
                  {i18n('ai.subscription.account.maskedAccount', connection.maskedAccount)}
                </div>
              ) : null}
              <div className={styles.actions}>
                {presentation.canStartConnect ? (
                  <Button type="primary" loading={loading} onClick={() => void startConnect(provider)}>
                    {i18n('ai.subscription.account.connectWithProvider', connection.displayName)}
                  </Button>
                ) : null}
                {presentation.canCancelConnect ? (
                  <Button loading={loading} onClick={() => void cancelConnect(provider)}>
                    {i18n('ai.subscription.account.cancelConnect')}
                  </Button>
                ) : null}
                {presentation.canRetryDiscovery ? (
                  <Button loading={loading} onClick={() => void retryDiscovery(provider)}>
                    {i18n('ai.subscription.account.retryDiscovery')}
                  </Button>
                ) : null}
                {presentation.canDisconnect ? (
                  <Button danger loading={loading} onClick={() => void disconnect(provider)}>
                    {i18n('ai.subscription.account.disconnect')}
                  </Button>
                ) : null}
                {presentation.canRetryDisconnect ? (
                  <Button danger loading={loading} onClick={() => void retryDisconnect(provider)}>
                    {i18n('ai.subscription.account.retryDisconnect')}
                  </Button>
                ) : null}
              </div>
            </div>
          );
        })
      )}

      <div className={styles.card}>
        <div className={styles.header}>
          <div className={styles.title}>{i18n('ai.subscription.migration.title')}</div>
        </div>
        <div className={styles.hint}>{i18n('ai.subscription.migration.desc')}</div>
        <div className={styles.actions}>
          <Button
            onClick={() => {
              setMigrationOpen(true);
            }}
          >
            {i18n('ai.subscription.migration.start')}
          </Button>
        </div>
      </div>

      <MigrationWizardModal
        open={migrationOpen}
        attempt={migrationAttempt}
        results={migrationResults}
        backendHasValidDefault={!!preferences.globalDefaultModelRefKey}
        backendDefaultModelRefKey={preferences.globalDefaultModelRefKey}
        onStart={() => {
          void beginMigration();
        }}
        onConfirm={(selectedDefaultItemId) => {
          void confirmMigration(selectedDefaultItemId);
        }}
        onRetryFailed={() => {
          void beginMigration();
        }}
        onClose={() => {
          setMigrationOpen(false);
          clearMigration();
        }}
      />
    </div>
  );
}
