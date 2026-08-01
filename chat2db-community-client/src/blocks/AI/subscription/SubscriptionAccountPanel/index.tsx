import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Spin, Tag } from 'antd';
import i18n from '@/i18n';
import type { AiProviderId } from '@/typings/aiSubscription';
import { useSubscriptionAiStore } from '@/store/aiSubscription';
import { listManageableProviders, presentAccountState } from '../accountState';
import { subscriptionRuntimeErrorI18nKey } from '../capability';
import MigrationWizardModal from '../MigrationWizardModal';
import { presentModelSnapshot } from '../modelSnapshot';
import { toModelRefKey } from '../modelRef';
import { useStyles } from './style';

/**
 * Subscription providers block for the unified model-config modal.
 * Multi-provider card template: ChatGPT live; non-eligible sources render as placeholders.
 * API-key models stay in the host modal's lower section.
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
    snapshots,
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
    snapshots: state.snapshots,
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

  const manageable = useMemo(() => listManageableProviders(providers), [providers]);
  const anyConnected = useMemo(
    () =>
      manageable.some((item) => {
        const p = presentAccountState(item);
        return (
          p.userState === 'connected' ||
          p.userState === 'connected_discovering' ||
          p.userState === 'connected_discovery_failed' ||
          p.userState === 'requires_reauth' ||
          p.userState === 'disconnecting'
        );
      }),
    [manageable],
  );
  // Non-eligible providers (e.g. SuperGrok waitlist) shown as future slots.
  const placeholderProviders = useMemo(
    () => providers.filter((item) => !item.eligible),
    [providers],
  );

  const modelsByProvider = useMemo(() => {
    const map = new Map<string, typeof snapshots>();
    for (const snapshot of snapshots) {
      const provider = snapshot.modelRef?.provider;
      if (!provider) {
        continue;
      }
      const list = map.get(provider) || [];
      list.push(snapshot);
      map.set(provider, list);
    }
    return map;
  }, [snapshots]);

  if (!hydrated) {
    return <Spin />;
  }

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

      {/* Onboarding only when no provider is mid-session connected */}
      {surfaceAvailable && !anyConnected ? (
        <div className={styles.onboardingCompact}>
          <div className={styles.onboardingLead}>{i18n('ai.subscription.config.subscribeLead')}</div>
          <details className={styles.howDetails}>
            <summary>{i18n('ai.subscription.config.howItWorks')}</summary>
            <ol className={styles.steps}>
              <li>{i18n('ai.subscription.onboarding.stepConnect')}</li>
              <li>{i18n('ai.subscription.onboarding.stepAuthorize')}</li>
              <li>{i18n('ai.subscription.onboarding.stepChooseModel')}</li>
            </ol>
          </details>
        </div>
      ) : null}

      {!surfaceAvailable ? null : manageable.length === 0 && placeholderProviders.length === 0 ? (
        <Alert type="info" showIcon message={i18n('ai.subscription.account.notEligible')} />
      ) : (
        <div className={styles.providerGrid}>
          {manageable.map((connection) => {
            const presentation = presentAccountState(connection);
            const provider = connection.provider as AiProviderId;
            const providerModels = modelsByProvider.get(provider) || [];
            const showModels =
              presentation.userState === 'connected' ||
              presentation.userState === 'connected_discovering' ||
              presentation.userState === 'connected_discovery_failed';
            const availableCount = providerModels.filter(
              (snapshot) => presentModelSnapshot(snapshot, connection).selectable,
            ).length;

            return (
              <div key={provider} className={styles.card} data-provider={provider}>
                <div className={styles.header}>
                  <div className={styles.titleBlock}>
                    <div className={styles.title}>{connection.displayName}</div>
                    {connection.maskedAccount ? (
                      <div className={styles.account}>
                        {i18n('ai.subscription.account.maskedAccount', connection.maskedAccount)}
                      </div>
                    ) : (
                      <div className={styles.account}>{i18n('ai.subscription.config.subscribeHint')}</div>
                    )}
                  </div>
                  <Tag
                    color={
                      presentation.userState === 'connected'
                        ? 'success'
                        : presentation.userState === 'connecting' ||
                            presentation.userState === 'connected_discovering'
                          ? 'processing'
                          : presentation.userState === 'connected_discovery_failed' ||
                              presentation.userState === 'requires_reauth'
                            ? 'warning'
                            : 'default'
                    }
                  >
                    {i18n(presentation.statusI18nKey as any)}
                  </Tag>
                </div>

                <div className={styles.actions}>
                  {presentation.canStartConnect ? (
                    <Button type="primary" size="small" loading={loading} onClick={() => void startConnect(provider)}>
                      {i18n('ai.subscription.account.connectWithProvider', connection.displayName)}
                    </Button>
                  ) : null}
                  {presentation.canCancelConnect ? (
                    <Button size="small" loading={loading} onClick={() => void cancelConnect(provider)}>
                      {i18n('ai.subscription.account.cancelConnect')}
                    </Button>
                  ) : null}
                  {presentation.canRetryDiscovery || presentation.userState === 'connected' ? (
                    <Button size="small" loading={loading} onClick={() => void retryDiscovery(provider)}>
                      {i18n('ai.subscription.config.refreshModels')}
                    </Button>
                  ) : null}
                  {presentation.canDisconnect ? (
                    <Button size="small" danger loading={loading} onClick={() => void disconnect(provider)}>
                      {i18n('ai.subscription.account.disconnect')}
                    </Button>
                  ) : null}
                  {presentation.canRetryDisconnect ? (
                    <Button size="small" danger loading={loading} onClick={() => void retryDisconnect(provider)}>
                      {i18n('ai.subscription.account.retryDisconnect')}
                    </Button>
                  ) : null}
                </div>

                {showModels ? (
                  <div className={styles.modelList}>
                    {presentation.userState === 'connected_discovering' && providerModels.length === 0 ? (
                      <div className={styles.modelEmpty}>{i18n('ai.subscription.account.discovering')}</div>
                    ) : providerModels.length === 0 ? (
                      <div className={styles.modelEmpty}>{i18n('ai.subscription.config.noModels')}</div>
                    ) : (
                      <>
                        <div className={styles.modelSummary}>
                          {i18n('ai.subscription.config.modelCount', availableCount, providerModels.length)}
                        </div>
                        <ul className={styles.modelNames}>
                          {providerModels.map((snapshot) => {
                            const key = snapshot.modelRefKey || toModelRefKey(snapshot.modelRef);
                            const presentationModel = presentModelSnapshot(snapshot, connection);
                            return (
                              <li
                                key={key}
                                className={
                                  presentationModel.selectable ? styles.modelNameItem : styles.modelNameItemMuted
                                }
                                title={snapshot.displayName}
                              >
                                <span className={styles.modelDot} aria-hidden />
                                <span className={styles.modelName}>{snapshot.displayName}</span>
                              </li>
                            );
                          })}
                        </ul>
                      </>
                    )}
                  </div>
                ) : null}
              </div>
            );
          })}

          {placeholderProviders.map((connection) => (
            <div key={connection.provider} className={styles.cardMuted} data-provider={connection.provider}>
              <div className={styles.header}>
                <div className={styles.titleBlock}>
                  <div className={styles.title}>{connection.displayName}</div>
                  <div className={styles.account}>{i18n('ai.subscription.config.futureProvider')}</div>
                </div>
                <Tag>{i18n('ai.subscription.account.notEligible')}</Tag>
              </div>
            </div>
          ))}
        </div>
      )}

      <details className={styles.advanced}>
        <summary>{i18n('ai.subscription.config.advancedMigration')}</summary>
        <div className={styles.advancedBody}>
          <div className={styles.hint}>{i18n('ai.subscription.migration.desc')}</div>
          <Button
            size="small"
            onClick={() => {
              setMigrationOpen(true);
            }}
          >
            {i18n('ai.subscription.migration.start')}
          </Button>
        </div>
      </details>

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
