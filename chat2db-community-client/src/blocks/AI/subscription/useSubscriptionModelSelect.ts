import { useEffect, useMemo } from 'react';
import i18n from '@/i18n';
import { useSubscriptionAiStore } from '@/store/aiSubscription';
import type { AiProviderId } from '@/typings/aiSubscription';
import {
  buildModelSelectSections,
  flattenModelSelectSections,
  isSubscriptionConnectOption,
  parseSubscriptionConnectProvider,
  type SubscriptionModelSelectOption,
} from './modelSelectGroups';

function formatSnapshotTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/**
 * Optional model-selector enrichment for packaged Community JCEF.
 * Callers keep API-key options intact and only merge subscription sections when available.
 */
export function useSubscriptionModelSelect(apiKeyOptions: readonly SubscriptionModelSelectOption[] = []) {
  const {
    surfaceAvailable,
    providers,
    snapshots,
    refreshSurface,
    startConnect,
  } = useSubscriptionAiStore((state) => ({
    surfaceAvailable: state.surfaceAvailable,
    providers: state.providers,
    snapshots: state.snapshots,
    refreshSurface: state.refreshSurface,
    startConnect: state.startConnect,
  }));

  useEffect(() => {
    void refreshSurface();
  }, [refreshSurface]);

  const sections = useMemo(
    () =>
      buildModelSelectSections({
        subscriptionEnabled: surfaceAvailable,
        connections: providers,
        snapshots,
        apiKeyOptions,
        formatSnapshotTime,
        recentlyConfirmedLabel: i18n('ai.subscription.model.recentlyConfirmed'),
        quickConnectLabel: (name) => i18n('ai.subscription.model.quickConnect', name),
        lastAvailableLabel: i18n('ai.subscription.model.lastAvailable'),
      }),
    [apiKeyOptions, providers, snapshots, surfaceAvailable],
  );

  const options = useMemo(() => flattenModelSelectSections(sections), [sections]);

  const handleSelectValue = async (value: string): Promise<'handled' | 'model'> => {
    if (!isSubscriptionConnectOption(value)) {
      return 'model';
    }
    const provider = parseSubscriptionConnectProvider(value) as AiProviderId | null;
    if (provider) {
      await startConnect(provider);
    }
    return 'handled';
  };

  return {
    surfaceAvailable,
    sections,
    options,
    handleSelectValue,
  };
}
