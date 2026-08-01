import type {
  AiModelSnapshotView,
  AiProviderConnectionView,
  AiSubscriptionCapability,
} from '@/typings/aiSubscription';
import { groupSubscriptionModels } from './modelSnapshot';
import { listQuickConnectProviders, presentAccountState } from './accountState';

export interface SubscriptionModelSelectOption {
  label: string;
  value: string;
  isDefault?: boolean;
  className?: string;
}

export const SUBSCRIPTION_CONNECT_OPTION_PREFIX = 'chat2db://action/subscription-connect/';

export function isSubscriptionConnectOption(value: unknown): boolean {
  return typeof value === 'string' && value.startsWith(SUBSCRIPTION_CONNECT_OPTION_PREFIX);
}

export function subscriptionConnectOptionValue(provider: string): string {
  return `${SUBSCRIPTION_CONNECT_OPTION_PREFIX}${provider}`;
}

export function parseSubscriptionConnectProvider(value: string): string | null {
  if (!isSubscriptionConnectOption(value)) {
    return null;
  }
  return value.slice(SUBSCRIPTION_CONNECT_OPTION_PREFIX.length) || null;
}

export interface GroupedModelSelectSection {
  key: string;
  label: string;
  options: SubscriptionModelSelectOption[];
}

/**
 * Build selector sections: subscription groups (with snapshot time) then API-key/custom models.
 * Quick-connect options appear only for eligible disconnected providers.
 */
export function buildModelSelectSections(params: {
  subscriptionEnabled: boolean;
  connections: readonly AiProviderConnectionView[];
  snapshots: readonly AiModelSnapshotView[];
  apiKeyOptions: readonly SubscriptionModelSelectOption[];
  formatSnapshotTime: (iso: string) => string;
  recentlyConfirmedLabel: string;
  quickConnectLabel: (providerDisplayName: string) => string;
  lastAvailableLabel: string;
}): GroupedModelSelectSection[] {
  const sections: GroupedModelSelectSection[] = [];

  if (params.subscriptionEnabled) {
    const groups = groupSubscriptionModels(params.snapshots, params.connections);
    for (const group of groups) {
      const timeLabel = group.snapshotUpdatedAt ? params.formatSnapshotTime(group.snapshotUpdatedAt) : '';
      sections.push({
        key: `subscription:${group.provider}`,
        label: timeLabel
          ? `${group.providerDisplayName} · ${params.recentlyConfirmedLabel} ${timeLabel}`
          : group.providerDisplayName,
        options: group.models.map((model) => ({
          label: model.displayName,
          value: model.modelRefKey,
          // disabled options still appear; Ant Design Select uses optionDisabled via className/label.
          className: model.selectable ? undefined : 'subscription-model-disabled',
        })),
      });
    }

    const quickConnect = listQuickConnectProviders(params.connections);
    if (quickConnect.length > 0) {
      sections.push({
        key: 'subscription-connect',
        label: 'ChatGPT',
        options: quickConnect.map((provider) => ({
          label: params.quickConnectLabel(provider.displayName),
          value: subscriptionConnectOptionValue(provider.provider),
        })),
      });
    }
  }

  if (params.apiKeyOptions.length > 0) {
    sections.push({
      key: 'api-key',
      label: 'API Key',
      options: [...params.apiKeyOptions],
    });
  }

  return sections;
}

export function flattenModelSelectSections(
  sections: readonly GroupedModelSelectSection[],
): SubscriptionModelSelectOption[] {
  return sections.flatMap((section) => section.options);
}

export function listSelectableSubscriptionModelRefKeys(
  snapshots: readonly AiModelSnapshotView[],
  connections: readonly AiProviderConnectionView[],
): string[] {
  return groupSubscriptionModels(snapshots, connections)
    .flatMap((group) => group.models)
    .filter((model) => model.selectable)
    .map((model) => model.modelRefKey);
}

export interface ChatGptConnectEntry {
  provider: 'OPENAI';
  action: 'CONNECT' | 'OPEN_SETTINGS';
}

export function resolveChatGptConnectEntry(params: {
  communityRuntime: boolean;
  packagedJcefDesktop: boolean;
  hydrated: boolean;
  surfaceAvailable: boolean;
  backendCapability: AiSubscriptionCapability | null;
  lastErrorCode: string | null;
  connections: readonly AiProviderConnectionView[];
}): ChatGptConnectEntry | null {
  if (!params.communityRuntime || !params.packagedJcefDesktop || !params.hydrated) {
    return null;
  }

  const chatGpt = params.connections.find(
    (connection) =>
      connection.provider === 'OPENAI' && connection.eligible && connection.showAccountManagement,
  );
  if (chatGpt && params.surfaceAvailable && presentAccountState(chatGpt).showQuickConnect) {
    return { provider: 'OPENAI', action: 'CONNECT' };
  }

  if (params.lastErrorCode === 'CAPABILITY_FETCH_FAILED' || params.lastErrorCode === 'PROVIDER_FETCH_FAILED') {
    return { provider: 'OPENAI', action: 'OPEN_SETTINGS' };
  }
  if (
    params.backendCapability &&
    !params.backendCapability.enabled &&
    params.backendCapability.disabledReason !== 'FEATURE_DISABLED' &&
    params.backendCapability.disabledReason !== 'NOT_COMMUNITY_RUNTIME' &&
    params.backendCapability.disabledReason !== 'NOT_DESKTOP'
  ) {
    return { provider: 'OPENAI', action: 'OPEN_SETTINGS' };
  }

  return null;
}

function selectableSnapshotKeys(snapshots: readonly AiModelSnapshotView[]): string[] {
  return snapshots
    .filter((snapshot) => snapshot.available && !snapshot.disabledReason && !!snapshot.modelRefKey)
    .map((snapshot) => snapshot.modelRefKey);
}

export function findNewSelectableSubscriptionModelKeys(
  previous: readonly AiModelSnapshotView[],
  current: readonly AiModelSnapshotView[],
): string[] {
  const previousKeys = new Set(selectableSnapshotKeys(previous));
  return selectableSnapshotKeys(current).filter((modelRefKey) => !previousKeys.has(modelRefKey));
}

export function decideSubscriptionModelRefresh(params: {
  previousSnapshots: readonly AiModelSnapshotView[];
  currentSnapshots: readonly AiModelSnapshotView[];
  postLoginGuidePending: boolean;
}): { reloadModelOptions: boolean; showPostLoginGuide: boolean } {
  const recoveredModelKeys = findNewSelectableSubscriptionModelKeys(
    params.previousSnapshots,
    params.currentSnapshots,
  );
  const reloadModelOptions = recoveredModelKeys.length > 0;
  return {
    reloadModelOptions,
    showPostLoginGuide: reloadModelOptions && params.postLoginGuidePending,
  };
}
