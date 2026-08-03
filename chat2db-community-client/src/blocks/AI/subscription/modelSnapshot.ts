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

export interface ReadyToUseModelOption {
  value: string;
  label: string;
  selectable?: boolean;
  defaultOption?: boolean;
  supportedReasoningEfforts?: readonly string[] | null;
}

const optionHasReasoningEfforts = (option: ReadyToUseModelOption): boolean =>
  Array.isArray(option.supportedReasoningEfforts) &&
  option.supportedReasoningEfforts.some((item) => !!String(item || '').trim());

/** ChatGPT subscription catalog product defaults when provider metadata is still loading. */
export const CHATGPT_SUBSCRIPTION_DEFAULT_REASONING_EFFORTS = ['low', 'medium', 'high', 'xhigh'] as const;

function normalizeReasoningEfforts(value: unknown): string[] {
  if (Array.isArray(value)) {
    return [...new Set(value.map((item) => String(item ?? '').trim().toLowerCase()).filter(Boolean))];
  }
  if (typeof value === 'string' && value.trim()) {
    // Some bridge/serialization paths may flatten the list into a comma-separated string.
    return [
      ...new Set(
        value
          .split(/[,\s|]+/)
          .map((item) => item.trim().toLowerCase())
          .filter(Boolean),
      ),
    ];
  }
  return [];
}

/**
 * Resolve reasoning capabilities for the currently selected model.
 *
 * Chat chrome keeps `selectedModel` in a global store (survives remounts) while the
 * detailed option map is local component state. Prefer the option map when present,
 * then subscription snapshots, then ChatGPT product defaults for subscription models so
 * the effort control is ready-to-use without opening the model dropdown.
 */
export function resolveModelReasoningCapabilities(params: {
  selectedModelValue?: string | null;
  optionSupportedReasoningEfforts?: readonly string[] | null;
  optionDefaultReasoningEffort?: string | null;
  snapshots?: readonly AiModelSnapshotView[] | null;
}): {
  supportedReasoningEfforts: string[];
  defaultReasoningEffort: string | null;
} {
  const fromOption = normalizeReasoningEfforts(params.optionSupportedReasoningEfforts);
  if (fromOption.length) {
    return {
      supportedReasoningEfforts: fromOption,
      defaultReasoningEffort: params.optionDefaultReasoningEffort?.trim().toLowerCase() || null,
    };
  }

  const selected = params.selectedModelValue?.trim() || '';
  if (!selected) {
    return { supportedReasoningEfforts: [], defaultReasoningEffort: null };
  }

  const snapshot = (params.snapshots || []).find((item) => {
    const key = item.modelRefKey || (item.modelRef ? toModelRefKey(item.modelRef) : '');
    if (key && key === selected) {
      return true;
    }
    // Tolerate partial keys / display-time mismatches (modelId suffix only).
    const modelId = item.modelRef?.modelId?.trim();
    return !!modelId && (selected === modelId || selected.endsWith(`::${modelId}`));
  });
  if (snapshot) {
    const fromSnapshot = normalizeReasoningEfforts(snapshot.supportedReasoningEfforts);
    if (fromSnapshot.length) {
      return {
        supportedReasoningEfforts: fromSnapshot,
        defaultReasoningEffort: snapshot.defaultReasoningEffort?.trim().toLowerCase() || null,
      };
    }
  }

  // Ready-to-use: any selected ChatGPT subscription model should show an effort control
  // immediately, even while catalog metadata is still hydrating after app open.
  if (selected.startsWith('SUBSCRIPTION::') || selected.startsWith('SUBSCRIPTION:')) {
    return {
      supportedReasoningEfforts: [...CHATGPT_SUBSCRIPTION_DEFAULT_REASONING_EFFORTS],
      defaultReasoningEffort: 'high',
    };
  }

  return { supportedReasoningEfforts: [], defaultReasoningEffort: null };
}

/**
 * Ready-to-use chat chrome: keep a valid selection when possible, otherwise pick a
 * selectable model. Prefer options that already advertise reasoning efforts so the
 * effort control can render without forcing the user to open the model dropdown.
 *
 * Never replace an explicit still-selectable current model (API-key or subscription)
 * just because another option advertises reasoning efforts — subscription is additive.
 */
export function resolveReadyToUseModelSelection(params: {
  options: readonly ReadyToUseModelOption[];
  currentValue?: string | null;
}): { value: string; label: string } | null {
  const selectable = params.options.filter((item) => item.selectable !== false && !!item.value);
  if (!selectable.length) {
    return null;
  }

  const currentValue = params.currentValue?.trim() || '';
  const current = currentValue ? selectable.find((item) => item.value === currentValue) : undefined;
  // Preserve any still-valid explicit selection regardless of effort metadata.
  if (current) {
    return { value: current.value, label: current.label };
  }

  const withEfforts = selectable.filter(optionHasReasoningEfforts);
  const preferred =
    withEfforts.find((item) => item.defaultOption) ||
    withEfforts[0] ||
    selectable.find((item) => item.defaultOption) ||
    selectable[0];
  return { value: preferred.value, label: preferred.label };
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
