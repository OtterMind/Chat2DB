import { createWithEqualityFn } from 'zustand/traditional';
import { shallow } from 'zustand/shallow';
import type {
  AiAttemptView,
  AiModelPreferenceView,
  AiModelSnapshotView,
  AiProviderConnectionView,
  AiProviderId,
  AiSecretImportAttemptView,
  AiSecretImportItemResult,
  AiSubscriptionCapability,
} from '@/typings/aiSubscription';
import { getAiSubscriptionClient } from '@/service/aiSubscription';
import { isSubscriptionAiSurfaceAvailable } from '@/blocks/AI/subscription/capability';
import { readSubscriptionSurfaceSignals } from '@/blocks/AI/subscription/capabilityRuntime';
import { groupSubscriptionModels } from '@/blocks/AI/subscription/modelSnapshot';
import { listManageableProviders, presentAccountState } from '@/blocks/AI/subscription/accountState';
import { encryptLegacyModelConfig } from '@/blocks/AI/subscription/secretImportCrypto';
import { resolveConfirmedDefaultItemId } from '@/blocks/AI/subscription/migrationPlan';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';

export interface SubscriptionAiState {
  hydrated: boolean;
  surfaceAvailable: boolean;
  capability: AiSubscriptionCapability | null;
  providers: AiProviderConnectionView[];
  snapshots: AiModelSnapshotView[];
  preferences: AiModelPreferenceView;
  attemptsByMessageId: Record<string, AiAttemptView[]>;
  /** Conversation-scoped explicit confirmation for legacy history. Not a secret. */
  legacyConfirmedModelRefKeyByConversation: Record<string, string>;
  activeConnectAttemptIdByProvider: Partial<Record<AiProviderId, string>>;
  providerBusy: boolean;
  migrationAttempt: AiSecretImportAttemptView | null;
  migrationResults: AiSecretImportItemResult[];
  lastErrorCode: string | null;
  loading: boolean;
}

export interface SubscriptionAiActions {
  refreshSurface: () => Promise<void>;
  refreshProvidersAndModels: () => Promise<void>;
  startConnect: (provider: AiProviderId) => Promise<void>;
  cancelConnect: (provider: AiProviderId) => Promise<void>;
  disconnect: (provider: AiProviderId) => Promise<void>;
  retryDisconnect: (provider: AiProviderId) => Promise<void>;
  retryDiscovery: (provider: AiProviderId) => Promise<void>;
  setProviderBusy: (busy: boolean) => void;
  confirmLegacyModel: (conversationId: string, modelRefKey: string) => void;
  clearLegacyModelConfirmation: (conversationId: string) => void;
  loadAttemptsForMessage: (messageId: string) => Promise<void>;
  beginMigration: () => Promise<void>;
  confirmMigration: (selectedDefaultItemId: string | null | undefined) => Promise<void>;
  clearMigration: () => void;
}

export type SubscriptionAiStore = SubscriptionAiState & SubscriptionAiActions;

const initialState: SubscriptionAiState = {
  hydrated: false,
  surfaceAvailable: false,
  capability: null,
  providers: [],
  snapshots: [],
  preferences: {
    globalDefaultModelRefKey: null,
    conversationModelRefKey: null,
  },
  attemptsByMessageId: {},
  legacyConfirmedModelRefKeyByConversation: {},
  activeConnectAttemptIdByProvider: {},
  providerBusy: false,
  migrationAttempt: null,
  migrationResults: [],
  lastErrorCode: null,
  loading: false,
};

export const useSubscriptionAiStore = createWithEqualityFn<SubscriptionAiStore>()((set, get) => ({
  ...initialState,

  refreshSurface: async () => {
    const signals = readSubscriptionSurfaceSignals(get().capability);
    const surfaceFromEnv = isSubscriptionAiSurfaceAvailable({
      communityRuntime: signals.communityRuntime,
      packagedJcefDesktop: signals.packagedJcefDesktop,
      backendCapability: null,
    });
    if (!surfaceFromEnv) {
      set({
        hydrated: true,
        surfaceAvailable: false,
        capability: null,
        providers: [],
        snapshots: [],
      });
      return;
    }

    set({ loading: true, lastErrorCode: null });
    try {
      const client = getAiSubscriptionClient();
      const capability = await client.getCapability();
      const surfaceAvailable = isSubscriptionAiSurfaceAvailable({
        communityRuntime: signals.communityRuntime,
        packagedJcefDesktop: signals.packagedJcefDesktop,
        backendCapability: capability,
      });
      set({ capability, surfaceAvailable, hydrated: true });
      if (surfaceAvailable) {
        await get().refreshProvidersAndModels();
      }
    } catch {
      // Backend capability is authoritative. Never expose a dormant or failed runtime from env alone.
      set({
        hydrated: true,
        surfaceAvailable: false,
        capability: { enabled: false, disabledReason: 'APP_SERVER_UNAVAILABLE' },
        providers: [],
        snapshots: [],
        lastErrorCode: 'CAPABILITY_FETCH_FAILED',
      });
    } finally {
      set({ loading: false });
    }
  },

  refreshProvidersAndModels: async () => {
    if (!get().surfaceAvailable && get().hydrated) {
      return;
    }
    set({ loading: true, lastErrorCode: null });
    try {
      const client = getAiSubscriptionClient();
      const [providers, snapshots, preferences] = await Promise.all([
        client.listProviders(),
        client.listModelSnapshots(),
        client.getPreferences(),
      ]);
      set({
        providers: providers || [],
        snapshots: snapshots || [],
        preferences: preferences || initialState.preferences,
        surfaceAvailable: true,
        hydrated: true,
      });
    } catch {
      set({ lastErrorCode: 'PROVIDER_FETCH_FAILED' });
    } finally {
      set({ loading: false });
    }
  },

  startConnect: async (provider) => {
    set({ loading: true, lastErrorCode: null });
    try {
      const result = await getAiSubscriptionClient().startConnect(provider);
      if (result.status !== 'STARTED' || !result.attemptId) {
        set({ lastErrorCode: result.errorCode || 'CONNECT_FAILED' });
        return;
      }
      set((state) => ({
        activeConnectAttemptIdByProvider: {
          ...state.activeConnectAttemptIdByProvider,
          [provider]: result.attemptId,
        },
      }));
      await get().refreshProvidersAndModels();
      // Login completion arrives asynchronously from the app-server. Poll the secret-free
      // provider state for up to the backend attempt TTL; no auth URL/token enters renderer state.
      const startedAt = Date.now();
      const poll = async () => {
        if (Date.now() - startedAt > 10 * 60_000) return;
        await get().refreshProvidersAndModels();
        const state = get().providers.find((item) => item.provider === provider)?.state;
        if (state === 'CONNECTING') window.setTimeout(() => void poll(), 1500);
        else if (state === 'CONNECTED' || state === 'DISCOVERY_FAILED') {
          set((current) => {
            const next = { ...current.activeConnectAttemptIdByProvider };
            delete next[provider];
            return { activeConnectAttemptIdByProvider: next };
          });
        }
      };
      window.setTimeout(() => void poll(), 1500);
    } catch {
      set({ lastErrorCode: 'CONNECT_FAILED' });
    } finally {
      set({ loading: false });
    }
  },

  cancelConnect: async (provider) => {
    const attemptId = get().activeConnectAttemptIdByProvider[provider];
    if (!attemptId) {
      return;
    }
    set({ loading: true, lastErrorCode: null });
    try {
      await getAiSubscriptionClient().cancelConnect({ provider, attemptId });
      set((state) => {
        const next = { ...state.activeConnectAttemptIdByProvider };
        delete next[provider];
        return { activeConnectAttemptIdByProvider: next };
      });
      await get().refreshProvidersAndModels();
    } catch {
      set({ lastErrorCode: 'CANCEL_CONNECT_FAILED' });
    } finally {
      set({ loading: false });
    }
  },

  disconnect: async (provider) => {
    set({ loading: true, lastErrorCode: null });
    try {
      await getAiSubscriptionClient().disconnect(provider);
      await get().refreshProvidersAndModels();
    } catch {
      set({ lastErrorCode: 'DISCONNECT_FAILED' });
    } finally {
      set({ loading: false });
    }
  },

  retryDisconnect: async (provider) => {
    set({ loading: true, lastErrorCode: null });
    try {
      await getAiSubscriptionClient().retryDisconnect(provider);
      await get().refreshProvidersAndModels();
    } catch {
      set({ lastErrorCode: 'RETRY_DISCONNECT_FAILED' });
    } finally {
      set({ loading: false });
    }
  },

  retryDiscovery: async (provider) => {
    set({ loading: true, lastErrorCode: null });
    try {
      await getAiSubscriptionClient().retryDiscovery(provider);
      await get().refreshProvidersAndModels();
    } catch {
      set({ lastErrorCode: 'RETRY_DISCOVERY_FAILED' });
    } finally {
      set({ loading: false });
    }
  },

  setProviderBusy: (busy) => set({ providerBusy: busy }),

  confirmLegacyModel: (conversationId, modelRefKey) => {
    set((state) => ({
      legacyConfirmedModelRefKeyByConversation: {
        ...state.legacyConfirmedModelRefKeyByConversation,
        [conversationId]: modelRefKey,
      },
    }));
  },

  clearLegacyModelConfirmation: (conversationId) => {
    set((state) => {
      const next = { ...state.legacyConfirmedModelRefKeyByConversation };
      delete next[conversationId];
      return { legacyConfirmedModelRefKeyByConversation: next };
    });
  },

  loadAttemptsForMessage: async (messageId) => {
    try {
      const attempts = await getAiSubscriptionClient().listAttempts({ messageId });
      set((state) => ({
        attemptsByMessageId: {
          ...state.attemptsByMessageId,
          [messageId]: attempts || [],
        },
      }));
    } catch {
      set({ lastErrorCode: 'ATTEMPT_FETCH_FAILED' });
    }
  },

  beginMigration: async () => {
    set({ loading: true, lastErrorCode: null });
    try {
      const candidates = readLegacyMigrationCandidates();
      if (candidates.length === 0) {
        set({ lastErrorCode: 'MIGRATION_NO_LOCAL_CONFIGS', migrationAttempt: null });
        return;
      }
      const start = await getAiSubscriptionClient().createSecretImportAttempt();
      if (!start.publicKeySpkiBase64 || !start.expiresAtEpochMs) throw new Error('MIGRATION_START_INVALID');
      const attempt: AiSecretImportAttemptView = {
        ...start,
        items: candidates.map(({ config, itemId }, index) => ({
          itemId,
          configName: String(config.name || config.model || `Model ${index + 1}`),
          provider: String(config.provider || 'OPENAI'),
          status: 'PENDING',
        })),
        completed: false,
      };
      set({ migrationAttempt: attempt, migrationResults: [] });
    } catch {
      set({ lastErrorCode: 'MIGRATION_START_FAILED', migrationAttempt: null });
    } finally {
      set({ loading: false });
    }
  },

  confirmMigration: async (selectedDefaultItemId) => {
    const attempt = get().migrationAttempt;
    if (!attempt || !attempt.publicKeySpkiBase64 || !attempt.expiresAtEpochMs) {
      set({ lastErrorCode: 'MIGRATION_ATTEMPT_MISSING' });
      return;
    }
    set({ loading: true, lastErrorCode: null });
    let candidates: LegacyMigrationCandidate[] = [];
    try {
      candidates = readLegacyMigrationCandidates();
      const confirmedDefaultItemId = resolveConfirmedDefaultItemId({
        backendHasValidDefault: !!get().preferences.globalDefaultModelRefKey,
        itemIds: attempt.items.map((item) => item.itemId),
        selectedItemId: selectedDefaultItemId,
      });
      const candidateByItemId = new Map(candidates.map((candidate) => [candidate.itemId, candidate]));
      const results: AiSecretImportItemResult[] = [];
      for (const item of attempt.items) {
        const itemId = item.itemId;
        const candidate = candidateByItemId.get(itemId);
        let result: AiSecretImportItemResult;
        if (!candidate) {
          result = { itemId, success: false, status: 'FAILED', errorCode: 'LOCAL_ITEM_MISSING' };
        } else {
          try {
            const { config } = candidate;
            set((state) => ({
              migrationAttempt: state.migrationAttempt
                ? {
                    ...state.migrationAttempt,
                    items: state.migrationAttempt.items.map((current) =>
                      current.itemId === itemId ? { ...current, status: 'IMPORTING' } : current,
                    ),
                  }
                : null,
            }));
            const envelope = await encryptLegacyModelConfig({
              attemptId: attempt.attemptId,
              itemId,
              publicKeySpkiBase64: attempt.publicKeySpkiBase64,
              expiresAtEpochMs: attempt.expiresAtEpochMs,
              payload: {
                id: config.id,
                name: config.name,
                provider: config.provider,
                model: config.model,
                apiKey: config.apiKey,
                baseUrl: config.baseUrl,
                projectId: config.projectId,
                location: config.location,
                temperature: config.temperature,
                maxTokens: config.maxTokens,
                enabled: config.enabled,
                defaultConfig: itemId === confirmedDefaultItemId,
              },
              confirmDefault: itemId === confirmedDefaultItemId,
            });
            result = await getAiSubscriptionClient().submitSecretImportEnvelope(envelope);
          } catch {
            result = { itemId, success: false, status: 'FAILED', errorCode: 'MIGRATION_ITEM_FAILED' };
          }
        }
        results.push(result);
        if (result.success && candidate) {
          try {
            const storageKey = runtimeEditionConfig.aiModelConfigStorageKey;
            const stored = JSON.parse(localStorage.getItem(storageKey) || '[]') as any[];
            const remaining = Array.isArray(stored)
              ? stored.filter((config) => `legacy-${config?.[SECRET_IMPORT_ITEM_ID_FIELD] || ''}` !== itemId)
              : [];
            localStorage.setItem(storageKey, JSON.stringify(remaining));
          } catch {
            // The backend import remains successful. Keep the local item and surface cleanup failure;
            // a retry in this attempt is still idempotent and cannot perform a second write.
            result = { ...result, success: false, status: 'FAILED', errorCode: 'LOCAL_DELETE_FAILED' };
            results[results.length - 1] = result;
          }
          candidate.config.apiKey = undefined;
        }
        set((state) => ({
          migrationResults: [...results],
          migrationAttempt: state.migrationAttempt
            ? {
                ...state.migrationAttempt,
                items: state.migrationAttempt.items.map((currentItem) =>
                  currentItem.itemId === itemId
                    ? {
                        ...currentItem,
                        status: result.success ? 'SUCCEEDED' : 'FAILED',
                        errorCode: result.errorCode,
                      }
                    : currentItem,
                ),
              }
            : null,
        }));
      }
      let backendAttemptCompleted = false;
      try {
        await getAiSubscriptionClient().completeSecretImportAttempt(attempt.attemptId);
        backendAttemptCompleted = true;
      } catch {
        await getAiSubscriptionClient()
          .cancelSecretImportAttempt(attempt.attemptId)
          .catch(() => undefined);
        set({ lastErrorCode: 'MIGRATION_COMPLETE_FAILED' });
      }
      const allSucceeded = backendAttemptCompleted
        && results.length === attempt.items.length
        && results.every((result) => result.success);
      set((state) => ({
        migrationAttempt: state.migrationAttempt
          ? { ...state.migrationAttempt, completed: allSucceeded }
          : null,
      }));
      if (allSucceeded) await get().refreshProvidersAndModels();
    } catch {
      set({ lastErrorCode: 'MIGRATION_CONFIRM_FAILED' });
    } finally {
      candidates.forEach((candidate) => {
        candidate.config.apiKey = undefined;
      });
      set({ loading: false });
    }
  },

  clearMigration: () => {
    const attempt = get().migrationAttempt;
    if (attempt && !attempt.completed) {
      void getAiSubscriptionClient()
        .cancelSecretImportAttempt(attempt.attemptId)
        .catch(() => undefined);
    }
    set({ migrationAttempt: null, migrationResults: [] });
  },
}), shallow);

interface LegacyMigrationCandidate {
  config: Record<string, any>;
  storageIndex: number;
  itemId: string;
}

const SECRET_IMPORT_ITEM_ID_FIELD = '__chat2dbSecretImportItemId';

function newSecretImportItemId(): string {
  if (typeof crypto?.randomUUID === 'function') return crypto.randomUUID();
  if (typeof crypto?.getRandomValues !== 'function') throw new Error('SECURE_RANDOM_UNAVAILABLE');
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
}

function readLegacyMigrationCandidates(): LegacyMigrationCandidate[] {
  const storageKey = runtimeEditionConfig.aiModelConfigStorageKey;
  const raw = localStorage.getItem(storageKey);
  const legacyConfigs = raw ? JSON.parse(raw) : [];
  if (!Array.isArray(legacyConfigs)) return [];
  let changed = false;
  const candidates = legacyConfigs
    .map((config, storageIndex) => {
      if (!config || typeof config.apiKey !== 'string' || !config.apiKey.trim()) return null;
      if (typeof config[SECRET_IMPORT_ITEM_ID_FIELD] !== 'string'
        || !config[SECRET_IMPORT_ITEM_ID_FIELD].trim()) {
        config[SECRET_IMPORT_ITEM_ID_FIELD] = newSecretImportItemId();
        changed = true;
      }
      return {
        config,
        storageIndex,
        itemId: `legacy-${config[SECRET_IMPORT_ITEM_ID_FIELD]}`,
      };
    })
    .filter((candidate): candidate is LegacyMigrationCandidate => candidate !== null);
  if (changed) localStorage.setItem(storageKey, JSON.stringify(legacyConfigs));
  return candidates;
}

export function selectManageableProviders(state: SubscriptionAiState) {
  return listManageableProviders(state.providers);
}

export function selectGroupedSubscriptionModels(state: SubscriptionAiState) {
  return groupSubscriptionModels(state.snapshots, state.providers);
}

export function selectProviderPresentation(state: SubscriptionAiState, provider: AiProviderId) {
  const connection = state.providers.find((item) => item.provider === provider);
  return connection ? presentAccountState(connection) : null;
}
