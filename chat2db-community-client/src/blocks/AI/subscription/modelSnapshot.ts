import type { AiModelSnapshotView, AiProviderConnectionView } from '@/typings/aiSubscription';
import { presentAccountState } from './accountState';
import { isSubscriptionModelRef, toModelRefKey } from './modelRef';

export interface SubscriptionModelGroup {
  provider: AiProviderConnectionView['provider'];
  providerDisplayName: string;
  /** ISO timestamp of the freshest available snapshot in the group, when any. */
  snapshotUpdatedAt: string | null;
  /** All models use “recently confirmed available” wording, never permanent entitlement. */
  availabilityWordingI18nKey: 'ai.subscription.model.recentlyConfirmed';
  models: Array<
    AiModelSnapshotView & {
      selectable: boolean;
      disabledI18nKey?: string;
    }
  >;
}

export interface ModelSnapshotPresentation {
  selectable: boolean;
  disabledI18nKey?: string;
  /** Relative/absolute snapshot time for selector subtitle. */
  snapshotTimeLabelSource: string;
  wordingI18nKey: 'ai.subscription.model.recentlyConfirmed';
}

export interface ReasoningEffortSelection {
  value: string | null;
  options: string[];
}

/** Keep provider ordering; prefer the user's existing choice, then product default High. */
export function resolveReasoningEffortSelection(params: {
  supportedReasoningEfforts?: readonly string[] | null;
  defaultReasoningEffort?: string | null;
  previousReasoningEffort?: string | null;
}): ReasoningEffortSelection {
  const options = [...new Set((params.supportedReasoningEfforts || []).map((item) => item.trim().toLowerCase()))]
    .filter(Boolean);
  if (!options.length) {
    return { value: null, options };
  }
  const previous = params.previousReasoningEffort?.trim().toLowerCase();
  if (previous && options.includes(previous)) {
    return { value: previous, options };
  }
  if (options.includes('high')) {
    return { value: 'high', options };
  }
  const providerDefault = params.defaultReasoningEffort?.trim().toLowerCase();
  if (providerDefault && options.includes(providerDefault)) {
    return { value: providerDefault, options };
  }
  return { value: options[0], options };
}

/**
 * Stale or failed snapshots remain visible only as disabled historical information.
 * Discovery failure leaves the account connected but no subscription model is selectable.
 */
export function presentModelSnapshot(
  snapshot: AiModelSnapshotView,
  connection?: AiProviderConnectionView | null,
): ModelSnapshotPresentation {
  const wordingI18nKey = 'ai.subscription.model.recentlyConfirmed' as const;
  const base = {
    wordingI18nKey,
    snapshotTimeLabelSource: snapshot.discoveredAt,
  };

  if (connection) {
    const account = presentAccountState(connection);
    if (!account.canSendWithSubscriptionModels) {
      return {
        ...base,
        selectable: false,
        disabledI18nKey:
          account.userState === 'connected_discovery_failed' || account.userState === 'connected_discovering'
            ? 'ai.subscription.model.temporarilyUnavailable'
            : account.userState === 'requires_reauth'
            ? 'ai.subscription.model.requiresReauth'
            : 'ai.subscription.model.disconnected',
      };
    }
  }

  if (!snapshot.available) {
    return {
      ...base,
      selectable: false,
      disabledI18nKey:
        snapshot.disabledReason === 'STALE'
          ? 'ai.subscription.model.lastAvailable'
          : snapshot.disabledReason === 'REJECTED'
          ? 'ai.subscription.model.rejected'
          : snapshot.disabledReason === 'QUOTA_EXHAUSTED'
          ? 'ai.subscription.model.quotaExhausted'
          : 'ai.subscription.model.temporarilyUnavailable',
    };
  }

  return {
    ...base,
    selectable: true,
  };
}

export function groupSubscriptionModels(
  snapshots: readonly AiModelSnapshotView[],
  connections: readonly AiProviderConnectionView[],
): SubscriptionModelGroup[] {
  const connectionByProvider = new Map(connections.map((item) => [item.provider, item]));
  const groups = new Map<string, SubscriptionModelGroup>();

  for (const snapshot of snapshots) {
    if (!isSubscriptionModelRef(snapshot.modelRef)) {
      continue;
    }
    const provider = snapshot.modelRef.provider;
    const connection = connectionByProvider.get(provider);
    const presentation = presentModelSnapshot(snapshot, connection);
    const existing = groups.get(provider);
    const entry = {
      ...snapshot,
      modelRefKey: snapshot.modelRefKey || toModelRefKey(snapshot.modelRef),
      selectable: presentation.selectable,
      disabledI18nKey: presentation.disabledI18nKey,
    };

    if (!existing) {
      groups.set(provider, {
        provider,
        providerDisplayName: connection?.displayName || provider,
        snapshotUpdatedAt: snapshot.discoveredAt,
        availabilityWordingI18nKey: 'ai.subscription.model.recentlyConfirmed',
        models: [entry],
      });
      continue;
    }

    existing.models.push(entry);
    if (
      !existing.snapshotUpdatedAt ||
      Date.parse(snapshot.discoveredAt) > Date.parse(existing.snapshotUpdatedAt)
    ) {
      existing.snapshotUpdatedAt = snapshot.discoveredAt;
    }
  }

  return [...groups.values()];
}

/**
 * After discovery success, never auto-select a model or overwrite a valid global default.
 * Selector should highlight newly available models for explicit user choice.
 */
export function resolvePostDiscoverySelection(params: {
  previousSelectedModelRefKey?: string | null;
  validGlobalDefaultModelRefKey?: string | null;
  availableModelRefKeys: readonly string[];
}): { selectedModelRefKey: string | null; requireExplicitChoice: boolean } {
  const available = new Set(params.availableModelRefKeys);
  if (params.previousSelectedModelRefKey && available.has(params.previousSelectedModelRefKey)) {
    return {
      selectedModelRefKey: params.previousSelectedModelRefKey,
      requireExplicitChoice: false,
    };
  }
  if (params.validGlobalDefaultModelRefKey && available.has(params.validGlobalDefaultModelRefKey)) {
    // Keep global default as preselection only when it was already the conversation selection path.
    // Post-login always requires explicit choice if the prior selection is gone.
    return {
      selectedModelRefKey: null,
      requireExplicitChoice: true,
    };
  }
  return {
    selectedModelRefKey: null,
    requireExplicitChoice: true,
  };
}
