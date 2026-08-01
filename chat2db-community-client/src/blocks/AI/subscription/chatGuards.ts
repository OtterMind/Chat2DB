import type {
  AiModelSnapshotView,
  AiProviderConnectionView,
  AiSendBlockReason,
} from '@/typings/aiSubscription';
import { resolveLegacySendGate } from './legacyHistory';
import { evaluateSubscriptionSendGate } from './sendGate';
import { isSubscriptionModelRef, parseModelRefKey } from './modelRef';

/** Minimal option shape used by the chat send guard (avoids pulling service modules into pure tests). */
export interface ChatSendModelOption {
  value: string;
  label: string;
  provider?: string;
  model?: string;
  subscriptionOption?: boolean;
  accessType?: 'API_KEY' | 'SUBSCRIPTION';
  selectable?: boolean;
}

export interface ChatSendGuardInput {
  surfaceAvailable: boolean;
  selectedModelValue?: string | null;
  modelOption?: ChatSendModelOption | null;
  connections: readonly AiProviderConnectionView[];
  snapshots: readonly AiModelSnapshotView[];
  providerBusy: boolean;
  conversationId?: string | null;
  conversationHasLegacyMessages: boolean;
  legacyConfirmedModelRefKey?: string | null;
  /** Capability disabledReason when surface is off — improves user-facing copy. */
  capabilityDisabledReason?: string | null;
  lastErrorCode?: string | null;
}

export interface ChatSendGuardResult {
  allowed: boolean;
  blockReason: AiSendBlockReason;
  /** Open legacy confirmation modal instead of sending. */
  needsLegacyModelConfirm: boolean;
  feedbackI18nKey?: string;
}

const BLOCK_I18N: Partial<Record<AiSendBlockReason, string>> = {
  SURFACE_DISABLED: 'ai.subscription.surface.unavailable',
  NOT_CONNECTED: 'ai.subscription.account.disconnected',
  CONNECTING: 'ai.subscription.account.connecting',
  DISCOVERY_FAILED: 'ai.subscription.account.discoveryFailed',
  DISCONNECTING: 'ai.subscription.account.disconnecting',
  DISCONNECT_FAILED: 'ai.subscription.account.disconnectFailed',
  REQUIRES_REAUTH: 'ai.subscription.account.requiresReauth',
  PROVIDER_DISABLED: 'ai.subscription.account.disabled',
  NO_AVAILABLE_MODEL: 'ai.select.model',
  MODEL_STALE_OR_DISABLED: 'ai.subscription.model.lastAvailable',
  PROVIDER_BUSY: 'ai.subscription.attempt.providerBusy',
  LEGACY_MODEL_UNCONFIRMED: 'ai.subscription.legacy.confirmTitle',
};

function resolveSurfaceBlockI18nKey(input: ChatSendGuardInput): string {
  // Prefer a precise capability/runtime reason over the generic packaged-desktop message.
  const disabled = input.capabilityDisabledReason;
  if (disabled === 'APP_SERVER_UNAVAILABLE') {
    return 'ai.subscription.error.appServerUnavailable';
  }
  if (disabled === 'FEATURE_DISABLED') {
    return 'ai.subscription.account.disabled';
  }
  if (disabled === 'NOT_COMMUNITY_RUNTIME') {
    return 'ai.subscription.surface.unavailable';
  }
  if (disabled === 'NOT_DESKTOP') {
    return 'ai.subscription.surface.unavailable';
  }
  if (input.lastErrorCode === 'CAPABILITY_FETCH_FAILED') {
    return 'ai.subscription.error.capabilityFetch';
  }
  return BLOCK_I18N.SURFACE_DISABLED || 'ai.subscription.surface.unavailable';
}

/**
 * Minimal chat integration point for subscription send rules.
 * API-key selections skip subscription-specific blocks (except legacy confirmation).
 */
export function evaluateChatSendGuard(input: ChatSendGuardInput): ChatSendGuardResult {
  const selected = input.selectedModelValue?.trim() || '';
  const modelRef = selected ? parseModelRefKey(selected) : null;
  const isSubscriptionSelection =
    !!input.modelOption?.subscriptionOption ||
    input.modelOption?.accessType === 'SUBSCRIPTION' ||
    (!!modelRef && isSubscriptionModelRef(modelRef));

  const legacyGate = resolveLegacySendGate({
    conversationHasLegacyMessages: input.conversationHasLegacyMessages,
    userConfirmedModelRefKey: input.legacyConfirmedModelRefKey,
    selectedModelRefKey: selected,
  });

  if (legacyGate.blocked) {
    return {
      allowed: false,
      blockReason: 'LEGACY_MODEL_UNCONFIRMED',
      needsLegacyModelConfirm: true,
      feedbackI18nKey: BLOCK_I18N.LEGACY_MODEL_UNCONFIRMED,
    };
  }

  if (!isSubscriptionSelection) {
    return {
      allowed: true,
      blockReason: 'NONE',
      needsLegacyModelConfirm: false,
    };
  }

  if (input.modelOption?.selectable === false) {
    return {
      allowed: false,
      blockReason: 'MODEL_STALE_OR_DISABLED',
      needsLegacyModelConfirm: false,
      feedbackI18nKey: BLOCK_I18N.MODEL_STALE_OR_DISABLED,
    };
  }

  const gate = evaluateSubscriptionSendGate({
    surfaceAvailable: input.surfaceAvailable,
    selectedModelRefKey: selected,
    connections: input.connections,
    snapshots: input.snapshots,
    providerBusy: input.providerBusy,
    legacyGate,
  });

  if (gate.allowed) {
    return {
      allowed: true,
      blockReason: 'NONE',
      needsLegacyModelConfirm: false,
    };
  }

  return {
    allowed: false,
    blockReason: gate.blockReason,
    needsLegacyModelConfirm: false,
    feedbackI18nKey:
      gate.blockReason === 'SURFACE_DISABLED'
        ? resolveSurfaceBlockI18nKey(input)
        : BLOCK_I18N[gate.blockReason],
  };
}
