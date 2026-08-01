import type {
  AiModelSnapshotView,
  AiProviderConnectionView,
  AiSendBlockReason,
} from '@/typings/aiSubscription';
import { presentAccountState } from './accountState';
import { findSnapshotForModelRefKey } from './modelOptionResolve';
import { presentModelSnapshot } from './modelSnapshot';
import { isSubscriptionModelRef, parseModelRefKey } from './modelRef';
import type { LegacySendGate } from './legacyHistory';

export interface SendGateInput {
  surfaceAvailable: boolean;
  selectedModelRefKey?: string | null;
  connections: readonly AiProviderConnectionView[];
  snapshots: readonly AiModelSnapshotView[];
  providerBusy?: boolean;
  legacyGate?: LegacySendGate | null;
}

export interface SendGateResult {
  allowed: boolean;
  blockReason: AiSendBlockReason;
  /** When blocked by provider busy, no new attempt should be created. */
  createAttempt: boolean;
}

/**
 * Central preflight for subscription-backed sends.
 * API-key models are out of scope here: callers skip this gate for non-subscription keys.
 */
export function evaluateSubscriptionSendGate(input: SendGateInput): SendGateResult {
  if (!input.surfaceAvailable) {
    return { allowed: false, blockReason: 'SURFACE_DISABLED', createAttempt: false };
  }

  if (input.legacyGate?.blocked) {
    return { allowed: false, blockReason: 'LEGACY_MODEL_UNCONFIRMED', createAttempt: false };
  }

  if (input.providerBusy) {
    return { allowed: false, blockReason: 'PROVIDER_BUSY', createAttempt: false };
  }

  const modelRefKey = input.selectedModelRefKey?.trim() || '';
  if (!modelRefKey) {
    return { allowed: false, blockReason: 'NO_AVAILABLE_MODEL', createAttempt: false };
  }

  const modelRef = parseModelRefKey(modelRefKey);
  if (!modelRef || !isSubscriptionModelRef(modelRef)) {
    // Non-subscription selection is handled by the existing API-key path.
    return { allowed: true, blockReason: 'NONE', createAttempt: true };
  }

  const connection = input.connections.find((item) => item.provider === modelRef.provider);
  if (!connection) {
    return { allowed: false, blockReason: 'NOT_CONNECTED', createAttempt: false };
  }

  const account = presentAccountState(connection);
  if (!account.canSendWithSubscriptionModels) {
    return {
      allowed: false,
      blockReason: account.sendBlockReason === 'NONE' ? 'NOT_CONNECTED' : account.sendBlockReason,
      createAttempt: false,
    };
  }

  const snapshot = findSnapshotForModelRefKey(modelRefKey, input.snapshots);
  if (!snapshot) {
    // Connection is ready but catalog is still hydrating — allow send; backend validates
    // the modelRefKey. Blocking here forces a dead-end "invalid model" after ready-to-use UI.
    if (account.canSendWithSubscriptionModels) {
      return { allowed: true, blockReason: 'NONE', createAttempt: true };
    }
    return { allowed: false, blockReason: 'NO_AVAILABLE_MODEL', createAttempt: false };
  }

  const modelPresentation = presentModelSnapshot(snapshot, connection);
  if (!modelPresentation.selectable) {
    return { allowed: false, blockReason: 'MODEL_STALE_OR_DISABLED', createAttempt: false };
  }

  return { allowed: true, blockReason: 'NONE', createAttempt: true };
}
