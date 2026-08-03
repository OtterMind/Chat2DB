import type { IModelOptionItem } from '@/service/aiStream';
import type { AiModelSnapshotView, AiProviderConnectionView } from '@/typings/aiSubscription';
import { isSubscriptionModelRef, parseModelRefKey, toModelRefKey } from './modelRef';
import {
  CHATGPT_SUBSCRIPTION_DEFAULT_REASONING_EFFORTS,
  presentModelSnapshot,
} from './modelSnapshot';

function findSnapshotForModelRefKey(
  selectedValue: string,
  snapshots: readonly AiModelSnapshotView[],
): AiModelSnapshotView | undefined {
  const selected = selectedValue.trim();
  if (!selected) {
    return undefined;
  }
  return snapshots.find((item) => {
    const key = item.modelRefKey || (item.modelRef ? toModelRefKey(item.modelRef) : '');
    if (key && key === selected) {
      return true;
    }
    const modelId = item.modelRef?.modelId?.trim();
    return !!modelId && (selected === modelId || selected.endsWith(`::${modelId}`));
  });
}

/**
 * Resolve the send-time model option.
 * selectedModel lives in global store; modelOptionMap is local and can lag. Rebuild a
 * subscription option from the snapshot catalog (or a parseable modelRefKey) so send
 * works without forcing the user to re-open the model dropdown.
 */
export function resolveChatModelOption(params: {
  selectedValue: string;
  optionMap: Record<string, IModelOptionItem>;
  snapshots: readonly AiModelSnapshotView[];
  connections?: readonly AiProviderConnectionView[];
}): IModelOptionItem | null {
  const selected = params.selectedValue?.trim() || '';
  if (!selected) {
    return null;
  }

  const fromMap = params.optionMap[selected];
  if (fromMap) {
    return fromMap;
  }

  const snapshot = findSnapshotForModelRefKey(selected, params.snapshots || []);
  if (snapshot) {
    const modelRef = snapshot.modelRef || parseModelRefKey(selected);
    if (!modelRef || !isSubscriptionModelRef(modelRef)) {
      return null;
    }
    const connection = (params.connections || []).find((item) => item.provider === modelRef.provider);
    const presentation = presentModelSnapshot(snapshot, connection);
    const modelRefKey = snapshot.modelRefKey || toModelRefKey(modelRef);
    return {
      value: modelRefKey,
      label: snapshot.displayName || modelRef.modelId,
      provider: modelRef.provider as IModelOptionItem['provider'],
      model: modelRef.modelId,
      modelRefKey,
      accessType: 'SUBSCRIPTION',
      subscriptionOption: true,
      selectable: presentation.selectable,
      disabledReason: snapshot.disabledReason,
      snapshotDiscoveredAt: snapshot.discoveredAt,
      supportedReasoningEfforts: snapshot.supportedReasoningEfforts?.length
        ? [...snapshot.supportedReasoningEfforts]
        : [...CHATGPT_SUBSCRIPTION_DEFAULT_REASONING_EFFORTS],
      defaultReasoningEffort: snapshot.defaultReasoningEffort || 'high',
      defaultOption: false,
      customOption: false,
    };
  }

  const modelRef = parseModelRefKey(selected);
  if (modelRef && isSubscriptionModelRef(modelRef)) {
    // Catalog not hydrated yet, but the selector already holds a valid subscription key.
    return {
      value: selected,
      label: modelRef.modelId,
      provider: modelRef.provider as IModelOptionItem['provider'],
      model: modelRef.modelId,
      modelRefKey: selected,
      accessType: 'SUBSCRIPTION',
      subscriptionOption: true,
      selectable: true,
      supportedReasoningEfforts: [...CHATGPT_SUBSCRIPTION_DEFAULT_REASONING_EFFORTS],
      defaultReasoningEffort: 'high',
      defaultOption: false,
      customOption: false,
    };
  }

  return null;
}

export { findSnapshotForModelRefKey };
