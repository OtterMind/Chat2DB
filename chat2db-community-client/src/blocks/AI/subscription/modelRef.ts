import type { AiAccessType, AiModelRef, AiProviderId, AiRouteKind } from '@/typings/aiSubscription';

const SEPARATOR = '::';

/**
 * Stable non-secret selector key derived from backend modelRef.
 * Format: accessType::provider::routeKind::modelId
 */
export function toModelRefKey(modelRef: AiModelRef): string {
  return [modelRef.accessType, modelRef.provider, modelRef.routeKind, modelRef.modelId].join(SEPARATOR);
}

export function parseModelRefKey(modelRefKey: string): AiModelRef | null {
  const parts = modelRefKey.split(SEPARATOR);
  if (parts.length !== 4) {
    return null;
  }
  const [accessType, provider, routeKind, modelId] = parts;
  if (!accessType || !provider || !routeKind || !modelId) {
    return null;
  }
  return {
    accessType: accessType as AiAccessType,
    provider: provider as AiProviderId,
    routeKind: routeKind as AiRouteKind,
    modelId,
  };
}

export function isSubscriptionModelRef(modelRef: AiModelRef): boolean {
  return (
    modelRef.accessType === 'SUBSCRIPTION' &&
    modelRef.provider === 'OPENAI' &&
    modelRef.routeKind === 'CHATGPT_CODEX_APP_SERVER'
  );
}

export function isValidChatGptSubscriptionModelRef(modelRef: AiModelRef): boolean {
  return isSubscriptionModelRef(modelRef) && modelRef.modelId.trim().length > 0;
}

export function createChatGptSubscriptionModelRef(modelId: string): AiModelRef {
  return {
    accessType: 'SUBSCRIPTION',
    provider: 'OPENAI',
    routeKind: 'CHATGPT_CODEX_APP_SERVER',
    modelId,
  };
}
