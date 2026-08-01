import type { AiMessageModelSnapshot } from '@/typings/aiSubscription';

export interface LegacyHistoryPresentation {
  legacyUnknown: boolean;
  badgeI18nKey?: 'ai.subscription.legacy.unknownModel';
  canReadAndCopy: boolean;
  requiresModelConfirmationBeforeSend: boolean;
  /** Valid global default may be preselected but never silently confirmed. */
  preselectedModelRefKey: string | null;
  autoConfirmGlobalDefault: boolean;
}

export interface LegacySendGate {
  blocked: boolean;
  blockReason: 'LEGACY_MODEL_UNCONFIRMED' | 'NONE';
  /** Once the user confirms, conversation remembers the model for subsequent messages only. */
  confirmedModelRefKey: string | null;
}

/**
 * Pre-upgrade messages without a model snapshot remain legacy/unknown.
 * Chat2DB never guesses or backfills historical model identity.
 */
export function presentLegacyMessage(
  message: AiMessageModelSnapshot,
  validGlobalDefaultModelRefKey?: string | null,
): LegacyHistoryPresentation {
  if (!message.legacyUnknown && message.modelRefKey) {
    return {
      legacyUnknown: false,
      canReadAndCopy: true,
      requiresModelConfirmationBeforeSend: false,
      preselectedModelRefKey: null,
      autoConfirmGlobalDefault: false,
    };
  }

  return {
    legacyUnknown: true,
    badgeI18nKey: 'ai.subscription.legacy.unknownModel',
    canReadAndCopy: true,
    requiresModelConfirmationBeforeSend: true,
    preselectedModelRefKey: validGlobalDefaultModelRefKey || null,
    autoConfirmGlobalDefault: false,
  };
}

export function conversationRequiresLegacyModelConfirm(
  messages: readonly AiMessageModelSnapshot[],
): boolean {
  return messages.some((message) => message.legacyUnknown || !message.modelRefKey);
}

/**
 * Opening a legacy conversation allows reading, but the next send requires explicit
 * model confirmation. A valid global default can be preselected only.
 */
export function resolveLegacySendGate(params: {
  conversationHasLegacyMessages: boolean;
  userConfirmedModelRefKey?: string | null;
  selectedModelRefKey?: string | null;
  availableModelRefKeys?: readonly string[];
}): LegacySendGate {
  if (!params.conversationHasLegacyMessages) {
    return {
      blocked: false,
      blockReason: 'NONE',
      confirmedModelRefKey: params.selectedModelRefKey || null,
    };
  }

  const confirmed = params.userConfirmedModelRefKey?.trim() || '';
  if (!confirmed) {
    return {
      blocked: true,
      blockReason: 'LEGACY_MODEL_UNCONFIRMED',
      confirmedModelRefKey: null,
    };
  }

  if (params.availableModelRefKeys && !params.availableModelRefKeys.includes(confirmed)) {
    return {
      blocked: true,
      blockReason: 'LEGACY_MODEL_UNCONFIRMED',
      confirmedModelRefKey: null,
    };
  }

  return {
    blocked: false,
    blockReason: 'NONE',
    confirmedModelRefKey: confirmed,
  };
}

/**
 * New model choice affects subsequent messages only; historical snapshots are immutable.
 */
export function applyConversationModelChoice(params: {
  historicalMessages: readonly AiMessageModelSnapshot[];
  confirmedModelRefKey: string;
  confirmedModelDisplayName: string;
  nextUserMessageId: string;
}): AiMessageModelSnapshot[] {
  const preserved = params.historicalMessages.map((message) => ({ ...message }));
  preserved.push({
    messageId: params.nextUserMessageId,
    modelRefKey: params.confirmedModelRefKey,
    modelDisplayName: params.confirmedModelDisplayName,
    legacyUnknown: false,
  });
  return preserved;
}
