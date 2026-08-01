import type {
  AiProviderConnectionState,
  AiProviderConnectionView,
  AiSendBlockReason,
} from '@/typings/aiSubscription';

export type AccountUserFacingState =
  | 'not_eligible'
  | 'disconnected'
  | 'connecting'
  | 'connected_discovering'
  | 'connected_discovery_failed'
  | 'connected'
  | 'requires_reauth'
  | 'disconnecting'
  | 'disconnect_failed'
  | 'disabled';

export interface AccountStatePresentation {
  userState: AccountUserFacingState;
  /** i18n key under ai.subscription.* */
  statusI18nKey: string;
  canSendWithSubscriptionModels: boolean;
  canStartConnect: boolean;
  canCancelConnect: boolean;
  canRetryDiscovery: boolean;
  canDisconnect: boolean;
  canRetryDisconnect: boolean;
  showQuickConnect: boolean;
  sendBlockReason: AiSendBlockReason;
}

const STATE_I18N: Record<AccountUserFacingState, string> = {
  not_eligible: 'ai.subscription.account.notEligible',
  disconnected: 'ai.subscription.account.disconnected',
  connecting: 'ai.subscription.account.connecting',
  connected_discovering: 'ai.subscription.account.discovering',
  connected_discovery_failed: 'ai.subscription.account.discoveryFailed',
  connected: 'ai.subscription.account.connected',
  requires_reauth: 'ai.subscription.account.requiresReauth',
  disconnecting: 'ai.subscription.account.disconnecting',
  disconnect_failed: 'ai.subscription.account.disconnectFailed',
  disabled: 'ai.subscription.account.disabled',
};

export function resolveAccountUserFacingState(connection: AiProviderConnectionView): AccountUserFacingState {
  if (!connection.eligible) {
    return 'not_eligible';
  }
  if (connection.reauthRequired) {
    return 'requires_reauth';
  }
  switch (connection.state) {
    case 'DISCONNECTED':
      return 'disconnected';
    case 'CONNECTING':
      return 'connecting';
    case 'CONNECTED':
      // Fresh connection before first successful discovery can still be "discovering"
      // when discoveredAt is absent; after discovery failure the backend uses DISCOVERY_FAILED.
      return connection.discoveredAt ? 'connected' : 'connected_discovering';
    case 'DISCOVERY_FAILED':
      return 'connected_discovery_failed';
    case 'DISCONNECTING':
      return 'disconnecting';
    case 'DISCONNECT_FAILED':
      return 'disconnect_failed';
    case 'DISABLED':
      return 'disabled';
    default:
      return 'disabled';
  }
}

export function presentAccountState(connection: AiProviderConnectionView): AccountStatePresentation {
  const userState = resolveAccountUserFacingState(connection);
  const base: AccountStatePresentation = {
    userState,
    statusI18nKey: STATE_I18N[userState],
    canSendWithSubscriptionModels: false,
    canStartConnect: false,
    canCancelConnect: false,
    canRetryDiscovery: false,
    canDisconnect: false,
    canRetryDisconnect: false,
    showQuickConnect: false,
    sendBlockReason: 'NONE',
  };

  switch (userState) {
    case 'not_eligible':
      return { ...base, sendBlockReason: 'PROVIDER_DISABLED' };
    case 'disconnected':
      return {
        ...base,
        canStartConnect: true,
        showQuickConnect: true,
        sendBlockReason: 'NOT_CONNECTED',
      };
    case 'connecting':
      return {
        ...base,
        canCancelConnect: true,
        sendBlockReason: 'CONNECTING',
      };
    case 'connected_discovering':
      return {
        ...base,
        canDisconnect: true,
        sendBlockReason: 'DISCOVERY_FAILED',
      };
    case 'connected_discovery_failed':
      return {
        ...base,
        canRetryDiscovery: true,
        canDisconnect: true,
        sendBlockReason: 'DISCOVERY_FAILED',
      };
    case 'connected':
      return {
        ...base,
        canSendWithSubscriptionModels: true,
        canDisconnect: true,
        sendBlockReason: 'NONE',
      };
    case 'requires_reauth':
      return {
        ...base,
        canStartConnect: true,
        showQuickConnect: true,
        sendBlockReason: 'REQUIRES_REAUTH',
      };
    case 'disconnecting':
      return {
        ...base,
        sendBlockReason: 'DISCONNECTING',
      };
    case 'disconnect_failed':
      return {
        ...base,
        canRetryDisconnect: true,
        sendBlockReason: 'DISCONNECT_FAILED',
      };
    case 'disabled':
      return {
        ...base,
        sendBlockReason: 'PROVIDER_DISABLED',
      };
    default:
      return base;
  }
}

export function isConnectionStateTerminalFence(state: AiProviderConnectionState): boolean {
  return state === 'DISCONNECTING' || state === 'DISCONNECT_FAILED' || state === 'DISABLED';
}

/**
 * SuperGrok / non-eligible providers must not appear as operable login buttons.
 * Settings may omit them entirely when showAccountManagement is false.
 */
export function listManageableProviders(connections: readonly AiProviderConnectionView[]): AiProviderConnectionView[] {
  return connections.filter((item) => item.eligible && item.showAccountManagement);
}

export function listQuickConnectProviders(
  connections: readonly AiProviderConnectionView[],
): AiProviderConnectionView[] {
  return connections.filter((item) => {
    if (!item.eligible) {
      return false;
    }
    const presentation = presentAccountState(item);
    return presentation.showQuickConnect;
  });
}
