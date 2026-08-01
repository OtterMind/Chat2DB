import type { AiSubscriptionCapability, AiSubscriptionDisabledReason } from '@/typings/aiSubscription';

export interface SubscriptionSurfaceSignals {
  /** Community runtime edition (`UMI_ENV=community` / `__RUNTIME_ENV__`). */
  communityRuntime: boolean;
  /** Packaged JCEF bridge present (`window.javaQuery`). */
  packagedJcefDesktop: boolean;
  /** Backend capability when available; null means not loaded yet. */
  backendCapability?: AiSubscriptionCapability | null;
}

export interface SubscriptionManagementEntrySignals extends SubscriptionSurfaceSignals {
  hydrated: boolean;
  lastErrorCode?: string | null;
}

/**
 * Renderer gate for subscription UI. Uses existing Community + JCEF helpers only.
 * Backend capability remains authoritative when present; unsupported surfaces stay hidden.
 */
export function isSubscriptionAiSurfaceAvailable(signals: SubscriptionSurfaceSignals): boolean {
  if (!signals.communityRuntime || !signals.packagedJcefDesktop) {
    return false;
  }
  if (signals.backendCapability && !signals.backendCapability.enabled) {
    return false;
  }
  return true;
}

export function resolveSurfaceDisabledReason(
  signals: SubscriptionSurfaceSignals,
): AiSubscriptionDisabledReason | null {
  if (!signals.communityRuntime) {
    return 'NOT_COMMUNITY_RUNTIME';
  }
  if (!signals.packagedJcefDesktop) {
    return 'NOT_DESKTOP';
  }
  if (signals.backendCapability && !signals.backendCapability.enabled) {
    return signals.backendCapability.disabledReason;
  }
  return null;
}

/**
 * Keep the default-off provider gate authoritative while exposing recoverable
 * desktop bridge failures in Settings so the user has somewhere to retry.
 */
export function isSubscriptionManagementEntryVisible(signals: SubscriptionManagementEntrySignals): boolean {
  if (!signals.communityRuntime || !signals.packagedJcefDesktop || !signals.hydrated) {
    return false;
  }
  if (signals.backendCapability?.enabled) {
    return true;
  }
  if (
    signals.backendCapability &&
    signals.backendCapability.disabledReason !== 'FEATURE_DISABLED' &&
    signals.backendCapability.disabledReason !== 'NOT_COMMUNITY_RUNTIME' &&
    signals.backendCapability.disabledReason !== 'NOT_DESKTOP'
  ) {
    return true;
  }
  return signals.lastErrorCode === 'CAPABILITY_FETCH_FAILED';
}

export function subscriptionRuntimeErrorI18nKey(errorCode: string | null | undefined): string | null {
  if (!errorCode) {
    return null;
  }
  if (errorCode === 'CAPABILITY_FETCH_FAILED') {
    return 'ai.subscription.error.capabilityFetch';
  }
  if (errorCode === 'PROVIDER_FETCH_FAILED') {
    return 'ai.subscription.error.providerFetch';
  }
  if (errorCode === 'KEYRING_UNAVAILABLE') {
    return 'ai.subscription.error.keyringUnavailable';
  }
  if (errorCode === 'APP_SERVER_UNAVAILABLE') {
    return 'ai.subscription.error.appServerUnavailable';
  }
  return 'ai.subscription.error.generic';
}
