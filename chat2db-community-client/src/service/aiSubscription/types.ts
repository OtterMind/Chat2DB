import type {
  AiAttemptView,
  AiModelPreferenceView,
  AiModelSnapshotView,
  AiProviderConnectionView,
  AiProviderId,
  AiSecretImportAttemptView,
  AiSecretImportEncryptedEnvelope,
  AiSecretImportItemResult,
  AiSubscriptionCapability,
} from '@/typings/aiSubscription';

export interface StartConnectResult {
  /** Backend-issued login/connect attempt id. Never a token. */
  attemptId: string;
  provider: AiProviderId;
  /** Optional browser open is owned by backend/JCEF; renderer only tracks attempt id. */
  status: 'STARTED' | 'BACKEND_NOT_READY' | 'FAILED';
  errorCode?: string | null;
}

export interface AiSubscriptionClient {
  getCapability(): Promise<AiSubscriptionCapability>;
  listProviders(): Promise<AiProviderConnectionView[]>;
  startConnect(provider: AiProviderId): Promise<StartConnectResult>;
  cancelConnect(params: { provider: AiProviderId; attemptId: string }): Promise<void>;
  disconnect(provider: AiProviderId): Promise<AiProviderConnectionView>;
  retryDisconnect(provider: AiProviderId): Promise<AiProviderConnectionView>;
  retryDiscovery(provider: AiProviderId): Promise<AiProviderConnectionView>;
  listModelSnapshots(): Promise<AiModelSnapshotView[]>;
  refreshModelSnapshots(provider?: AiProviderId): Promise<AiModelSnapshotView[]>;
  getPreferences(): Promise<AiModelPreferenceView>;
  setGlobalDefaultModel(modelRefKey: string): Promise<AiModelPreferenceView>;
  setConversationModel(params: {
    conversationId: string;
    modelRefKey: string;
  }): Promise<AiModelPreferenceView>;
  listAttempts(params: { messageId?: string; conversationId?: string }): Promise<AiAttemptView[]>;
  createSecretImportAttempt(): Promise<AiSecretImportAttemptView>;
  /**
   * Dedicated encrypted import channel. Implementations must not log envelope ciphertext
   * as plaintext secrets; renderer must not retain API keys in store state.
   */
  submitSecretImportEnvelope(
    envelope: AiSecretImportEncryptedEnvelope,
  ): Promise<AiSecretImportItemResult>;
  completeSecretImportAttempt(attemptId: string): Promise<AiSecretImportAttemptView>;
  cancelSecretImportAttempt(attemptId: string): Promise<void>;
}
