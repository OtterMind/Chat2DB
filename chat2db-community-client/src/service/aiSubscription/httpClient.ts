import createRequest from '@/service/base';
import type {
  AiAttemptView,
  AiModelPreferenceView,
  AiModelSnapshotView,
  AiProviderConnectionView,
  AiProviderId,
  AiSubscriptionCapability,
} from '@/typings/aiSubscription';
import type { AiSubscriptionClient, StartConnectResult } from './types';
import { secretImportRequest } from './secretImportTransport';

/** HTTP adapter for the secret-free subscription control plane. */
const getCapability = createRequest<void, AiSubscriptionCapability>('/api/v3/ai/subscription/capability');
const listProviders = createRequest<void, AiProviderConnectionView[]>('/api/v3/ai/subscription/providers');
const startConnect = createRequest<{ provider: AiProviderId }, StartConnectResult>(
  '/api/v3/ai/subscription/connect/start',
  { method: 'post' },
);
const cancelConnect = createRequest<{ provider: AiProviderId; attemptId: string }, void>(
  '/api/v3/ai/subscription/connect/cancel',
  { method: 'post' },
);
const disconnect = createRequest<{ provider: AiProviderId }, AiProviderConnectionView>(
  '/api/v3/ai/subscription/disconnect',
  { method: 'post' },
);
const retryDisconnect = createRequest<{ provider: AiProviderId }, AiProviderConnectionView>(
  '/api/v3/ai/subscription/disconnect/retry',
  { method: 'post' },
);
const retryDiscovery = createRequest<{ provider: AiProviderId }, AiProviderConnectionView>(
  '/api/v3/ai/subscription/discovery/retry',
  { method: 'post' },
);
const listModelSnapshots = createRequest<void, AiModelSnapshotView[]>('/api/v3/ai/subscription/models');
const refreshModelSnapshots = createRequest<{ provider?: AiProviderId }, AiModelSnapshotView[]>(
  '/api/v3/ai/subscription/models/refresh',
  { method: 'post' },
);
const getPreferences = createRequest<{ conversationId?: string } | void, AiModelPreferenceView>(
  '/api/v3/ai/subscription/preferences',
);
const setGlobalDefaultModel = createRequest<{ modelRefKey: string }, AiModelPreferenceView>(
  '/api/v3/ai/subscription/preferences/global-default',
  { method: 'post' },
);
const setConversationModel = createRequest<
  { conversationId: string; modelRefKey: string },
  AiModelPreferenceView
>('/api/v3/ai/subscription/preferences/conversation', { method: 'post' });
const listAttempts = createRequest<{ messageId?: string; conversationId?: string }, AiAttemptView[]>(
  '/api/v3/ai/subscription/attempts',
);
interface SecretImportStartRaw {
  attemptId: string;
  publicKeySpkiBase64: string;
  expiresAtEpochMs: number;
  schemaVersion: 1;
}

interface SecretImportItemRaw {
  itemId?: string;
  status?: 'SUCCEEDED' | 'ALREADY_IMPORTED' | 'FAILED';
  errorCode?: string | null;
}

export function createHttpAiSubscriptionClient(): AiSubscriptionClient {
  return {
    getCapability: () => getCapability(undefined as void),
    listProviders: () => listProviders(undefined as void).then((items) => items || []),
    startConnect: (provider) => startConnect({ provider }),
    cancelConnect: (params) => cancelConnect(params),
    disconnect: (provider) => disconnect({ provider }),
    retryDisconnect: (provider) => retryDisconnect({ provider }),
    retryDiscovery: (provider) => retryDiscovery({ provider }),
    listModelSnapshots: () => listModelSnapshots(undefined as void).then((items) => items || []),
    refreshModelSnapshots: (provider) => refreshModelSnapshots({ provider }),
    getPreferences: (conversationId) =>
      getPreferences(
        conversationId && conversationId.trim()
          ? { conversationId: conversationId.trim() }
          : (undefined as void),
      ),
    setGlobalDefaultModel: (modelRefKey) => setGlobalDefaultModel({ modelRefKey }),
    setConversationModel: (params) => setConversationModel(params),
    listAttempts: (params) => listAttempts(params).then((items) => items || []),
    createSecretImportAttempt: async () => {
      const start = await secretImportRequest<SecretImportStartRaw>('/api/ai/secret-import/start', {});
      return { ...start, items: [], completed: false };
    },
    submitSecretImportEnvelope: async (envelope) => {
      const result = await secretImportRequest<SecretImportItemRaw>('/api/ai/secret-import/item', envelope);
      const status = result.status || 'FAILED';
      return {
        itemId: result.itemId || envelope.itemId,
        success: status === 'SUCCEEDED' || status === 'ALREADY_IMPORTED',
        status,
        errorCode: result.errorCode,
      };
    },
    completeSecretImportAttempt: async (attemptId) => {
      await secretImportRequest<{ attemptId: string; status: string }>('/api/ai/secret-import/complete', {
        attemptId,
      });
      return { attemptId, items: [], completed: true };
    },
    cancelSecretImportAttempt: async (attemptId) => {
      await secretImportRequest<{ attemptId: string; status: string }>('/api/ai/secret-import/cancel', {
        attemptId,
      });
    },
  };
}
