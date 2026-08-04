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
import { createChatGptSubscriptionModelRef, toModelRefKey } from '@/blocks/AI/subscription/modelRef';
import type { AiSubscriptionClient, StartConnectResult } from './types';

export interface MockSubscriptionState {
  capability: AiSubscriptionCapability;
  providers: AiProviderConnectionView[];
  snapshots: AiModelSnapshotView[];
  preferences: AiModelPreferenceView;
  /** conversationId -> modelRefKey for preference isolation tests */
  conversationModels?: Record<string, string>;
  attempts: AiAttemptView[];
  importAttempts: AiSecretImportAttemptView[];
}

export function createDefaultMockSubscriptionState(): MockSubscriptionState {
  const modelRef = createChatGptSubscriptionModelRef('gpt-5');
  return {
    capability: { enabled: true, disabledReason: 'NONE' },
    providers: [
      {
        provider: 'OPENAI',
        displayName: 'ChatGPT',
        state: 'DISCONNECTED',
        fenceGeneration: 0,
        eligible: true,
        showAccountManagement: true,
      },
      {
        provider: 'XAI',
        displayName: 'SuperGrok',
        state: 'DISABLED',
        fenceGeneration: 0,
        eligible: false,
        showAccountManagement: false,
        disabledReason: 'FEATURE_DISABLED',
      },
    ],
    snapshots: [
      {
        modelRef,
        modelRefKey: toModelRefKey(modelRef),
        displayName: 'GPT-5',
        discoveredAt: '2026-07-31T10:00:00.000Z',
        available: true,
      },
    ],
    preferences: {
      globalDefaultModelRefKey: null,
      conversationModelRefKey: null,
    },
    conversationModels: {},
    attempts: [],
    importAttempts: [],
  };
}

/**
 * In-memory mockable backend contract for focused UI/store tests.
 * No OAuth tokens or API keys are stored.
 */
export function createMockAiSubscriptionClient(
  initial: MockSubscriptionState = createDefaultMockSubscriptionState(),
): AiSubscriptionClient & { state: MockSubscriptionState } {
  const state: MockSubscriptionState = {
    ...initial,
    providers: initial.providers.map((item) => ({ ...item })),
    snapshots: initial.snapshots.map((item) => ({ ...item })),
    attempts: [...initial.attempts],
    importAttempts: initial.importAttempts.map((item) => ({
      ...item,
      items: item.items.map((entry) => ({ ...entry })),
    })),
  };

  const updateProvider = (provider: AiProviderId, patch: Partial<AiProviderConnectionView>) => {
    const index = state.providers.findIndex((item) => item.provider === provider);
    if (index < 0) {
      throw new Error(`Unknown provider ${provider}`);
    }
    state.providers[index] = { ...state.providers[index], ...patch };
    return state.providers[index];
  };

  const client: AiSubscriptionClient & { state: MockSubscriptionState } = {
    state,
    async getCapability() {
      return state.capability;
    },
    async listProviders() {
      return state.providers.map((item) => ({ ...item }));
    },
    async startConnect(provider) {
      const current = state.providers.find((item) => item.provider === provider);
      if (!current?.eligible) {
        return {
          attemptId: '',
          provider,
          status: 'FAILED',
          errorCode: 'PROVIDER_NOT_ELIGIBLE',
        } satisfies StartConnectResult;
      }
      const attemptId = `connect-${provider}-${Date.now()}`;
      updateProvider(provider, { state: 'CONNECTING', reauthRequired: false });
      return { attemptId, provider, status: 'STARTED' };
    },
    async cancelConnect({ provider }) {
      updateProvider(provider, { state: 'DISCONNECTED', discoveredAt: null, maskedAccount: null });
    },
    async interruptActiveTurn(params) {
      return {
        interrupted: false,
        provider: params?.provider || 'OPENAI',
      };
    },
    async disconnect(provider) {
      updateProvider(provider, { state: 'DISCONNECTING' });
      // Mock durable credential deletion success path.
      return updateProvider(provider, {
        state: 'DISCONNECTED',
        maskedAccount: null,
        discoveredAt: null,
        reauthRequired: false,
        fenceGeneration: (state.providers.find((item) => item.provider === provider)?.fenceGeneration || 0) + 1,
      });
    },
    async retryDisconnect(provider) {
      return client.disconnect(provider);
    },
    async retryDiscovery(provider) {
      updateProvider(provider, {
        state: 'CONNECTED',
        discoveredAt: new Date().toISOString(),
        discoveryErrorCode: null,
      });
      return state.providers.find((item) => item.provider === provider)!;
    },
    async listModelSnapshots() {
      return state.snapshots.map((item) => ({ ...item }));
    },
    async refreshModelSnapshots() {
      return client.listModelSnapshots();
    },
    async getPreferences(conversationId) {
      if (conversationId && conversationId.trim()) {
        const key = state.conversationModels?.[conversationId.trim()] || null;
        return {
          globalDefaultModelRefKey: state.preferences.globalDefaultModelRefKey,
          conversationModelRefKey: key,
        };
      }
      return { ...state.preferences };
    },
    async setGlobalDefaultModel(modelRefKey) {
      state.preferences.globalDefaultModelRefKey = modelRefKey;
      return { ...state.preferences };
    },
    async setConversationModel({ conversationId, modelRefKey }) {
      if (!state.conversationModels) {
        state.conversationModels = {};
      }
      if (conversationId) {
        state.conversationModels[conversationId] = modelRefKey;
      }
      state.preferences.conversationModelRefKey = modelRefKey;
      return {
        globalDefaultModelRefKey: state.preferences.globalDefaultModelRefKey,
        conversationModelRefKey: modelRefKey,
      };
    },
    async listAttempts() {
      return state.attempts.map((item) => ({ ...item }));
    },
    async createSecretImportAttempt() {
      const attempt: AiSecretImportAttemptView = {
        attemptId: `import-${Date.now()}`,
        completed: false,
        publicKeySpkiBase64: 'mock-public-key',
        expiresAtEpochMs: Date.now() + 5 * 60_000,
        schemaVersion: 1,
        items: [],
      };
      state.importAttempts.push(attempt);
      return attempt;
    },
    async submitSecretImportEnvelope(envelope: AiSecretImportEncryptedEnvelope) {
      // Envelope is opaque; mock never inspects ciphertext as a secret value for storage.
      const result: AiSecretImportItemResult = {
        itemId: envelope.itemId,
        success: true,
      };
      return result;
    },
    async completeSecretImportAttempt(attemptId) {
      const attempt = state.importAttempts.find((item) => item.attemptId === attemptId);
      if (!attempt) {
        throw new Error('Unknown import attempt');
      }
      attempt.completed = true;
      return attempt;
    },
    async cancelSecretImportAttempt(attemptId) {
      const index = state.importAttempts.findIndex((item) => item.attemptId === attemptId);
      if (index >= 0) state.importAttempts.splice(index, 1);
    },
  };

  return client;
}
