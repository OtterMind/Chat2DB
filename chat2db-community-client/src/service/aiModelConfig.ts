import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { isSubscriptionAiSurfaceAvailable } from '@/blocks/AI/subscription/capability';
import { readSubscriptionSurfaceSignals } from '@/blocks/AI/subscription/capabilityRuntime';
import { groupSubscriptionModels } from '@/blocks/AI/subscription/modelSnapshot';
import { toModelRefKey } from '@/blocks/AI/subscription/modelRef';
import { getAiSubscriptionClient } from '@/service/aiSubscription';
import aiStreamService, { IModelOptionItem } from './aiStream';
import createRequest from './base';

export type AIProvider = 'OPENAI' | 'CLAUDE' | 'GEMINI';

export interface IAIModelConfigItem {
  id: string;
  name: string;
  provider: AIProvider;
  model: string;
  apiKey?: string;
  baseUrl?: string;
  projectId?: string;
  location?: string;
  temperature?: number;
  maxTokens?: number;
  enabled?: boolean;
  defaultConfig?: boolean;
  hasApiKey?: boolean;
  apiKeyMasked?: string;
  gmtModified?: string;
}

export interface IAIModelConfigSaveRequest {
  id?: string;
  name: string;
  provider: AIProvider;
  model: string;
  apiKey?: string;
  baseUrl?: string;
  projectId?: string;
  location?: string;
  temperature?: number;
  maxTokens?: number;
  enabled?: boolean;
  defaultConfig?: boolean;
}

export interface IAIModelConfigTestResult {
  success?: boolean;
  message?: string;
  statusCode?: number;
  endpoint?: string;
}

const LOCAL_STORAGE_KEY = runtimeEditionConfig.aiModelConfigStorageKey;

const listRemoteModelConfigs = createRequest<void, IAIModelConfigItem[]>('/api/v3/ai/model/config/list');
const saveRemoteModelConfig = createRequest<IAIModelConfigSaveRequest, IAIModelConfigItem>(
  '/api/v3/ai/model/config/save',
  {
    method: 'post',
  },
);
const deleteRemoteModelConfig = createRequest<{ id: string }, void>('/api/v3/ai/model/config/delete', {
  method: 'post',
});
const testRemoteModelConfig = createRequest<IAIModelConfigSaveRequest, IAIModelConfigTestResult>(
  '/api/v3/ai/model/config/test',
  {
    method: 'post',
    errorLevel: false,
  },
);

const normalizeLocalConfig = (config: IAIModelConfigItem): IAIModelConfigItem => ({
  ...config,
  enabled: config.enabled ?? true,
  defaultConfig: config.defaultConfig ?? false,
  hasApiKey: !!config.apiKey?.trim(),
  apiKeyMasked: maskApiKey(config.apiKey),
});

const maskApiKey = (apiKey?: string) => {
  if (!apiKey?.trim()) {
    return '';
  }
  if (apiKey.length <= 8) {
    return '****';
  }
  return `${apiKey.slice(0, 4)}****${apiKey.slice(-4)}`;
};

const sortConfigs = (configs: IAIModelConfigItem[]) =>
  [...configs].sort((a, b) => {
    if (!!a.defaultConfig !== !!b.defaultConfig) {
      return a.defaultConfig ? -1 : 1;
    }
    return (b.gmtModified || '').localeCompare(a.gmtModified || '');
  });

const loadLocalConfigs = (): IAIModelConfigItem[] => {
  try {
    const raw = localStorage.getItem(LOCAL_STORAGE_KEY);
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return sortConfigs(parsed.map(normalizeLocalConfig));
  } catch {
    return [];
  }
};

const persistLocalConfigs = (configs: IAIModelConfigItem[]) => {
  localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(sortConfigs(configs)));
};

const ensureOneDefaultConfig = (configs: IAIModelConfigItem[]) => {
  if (configs.length === 0) {
    return configs;
  }
  if (configs.some((item) => item.defaultConfig)) {
    return configs;
  }
  configs[0].defaultConfig = true;
  return configs;
};

const createLocalModelOption = (config: IAIModelConfigItem): IModelOptionItem => ({
  value: `config:${config.id}`,
  label: config.name || config.model,
  provider: config.provider,
  model: config.model,
  modelConfigId: config.id,
  customOption: true,
  defaultOption: !!config.defaultConfig,
});

export const listAIModelConfigs = async () => {
  if (runtimeEditionConfig.localPersistence) {
    return loadLocalConfigs();
  }
  return (await listRemoteModelConfigs(undefined as void)) || [];
};

export const saveAIModelConfig = async (payload: IAIModelConfigSaveRequest) => {
  if (!runtimeEditionConfig.localPersistence) {
    return saveRemoteModelConfig(payload);
  }

  const current = loadLocalConfigs();
  const now = new Date().toISOString();
  const existing = payload.id ? current.find((item) => item.id === payload.id) : undefined;

  const nextConfig: IAIModelConfigItem = normalizeLocalConfig({
    ...existing,
    ...payload,
    id:
      existing?.id ||
      payload.id ||
      `${Date.now()}-${Math.random()
        .toString(36)
        .slice(2, 10)}`,
    apiKey: payload.apiKey?.trim() ? payload.apiKey.trim() : existing?.apiKey,
    enabled: payload.enabled ?? true,
    defaultConfig: payload.defaultConfig ?? false,
    gmtModified: now,
  });

  let nextList = current.filter((item) => item.id !== nextConfig.id);
  nextList.unshift(nextConfig);

  if (nextConfig.defaultConfig) {
    nextList = nextList.map((item) => ({
      ...item,
      defaultConfig: item.id === nextConfig.id,
    }));
  }

  persistLocalConfigs(ensureOneDefaultConfig(nextList));
  return nextConfig;
};

export const deleteAIModelConfig = async (id: string) => {
  if (!runtimeEditionConfig.localPersistence) {
    await deleteRemoteModelConfig({ id });
    return;
  }

  const current = loadLocalConfigs().filter((item) => item.id !== id);
  persistLocalConfigs(ensureOneDefaultConfig(current));
};

export const testAIModelConfig = async (payload: IAIModelConfigSaveRequest) => {
  const nextPayload = { ...payload };
  if (runtimeEditionConfig.localPersistence && !nextPayload.apiKey?.trim() && nextPayload.id) {
    const localConfig = loadLocalConfigs().find((item) => item.id === nextPayload.id);
    if (localConfig?.apiKey?.trim()) {
      nextPayload.apiKey = localConfig.apiKey.trim();
    }
  }
  return testRemoteModelConfig(nextPayload);
};

const listSubscriptionModelOptions = async (): Promise<IModelOptionItem[]> => {
  const surface = readSubscriptionSurfaceSignals();
  if (
    !isSubscriptionAiSurfaceAvailable({
      communityRuntime: surface.communityRuntime,
      packagedJcefDesktop: surface.packagedJcefDesktop,
      backendCapability: null,
    })
  ) {
    return [];
  }

  try {
    const client = getAiSubscriptionClient();
    const [capability, providers, snapshots] = await Promise.all([
      client.getCapability(),
      client.listProviders(),
      client.listModelSnapshots(),
    ]);
    if (
      !isSubscriptionAiSurfaceAvailable({
        communityRuntime: surface.communityRuntime,
        packagedJcefDesktop: surface.packagedJcefDesktop,
        backendCapability: capability,
      })
    ) {
      return [];
    }

    return groupSubscriptionModels(snapshots || [], providers || []).flatMap((group) =>
      group.models.map((model) => {
        const modelRefKey = model.modelRefKey || toModelRefKey(model.modelRef);
        return {
          value: modelRefKey,
          label: model.displayName,
          provider: model.modelRef.provider as IModelOptionItem['provider'],
          model: model.modelRef.modelId,
          modelRefKey,
          accessType: 'SUBSCRIPTION',
          subscriptionOption: true,
          selectable: model.selectable,
          snapshotDiscoveredAt: model.discoveredAt,
          disabledReason: model.disabledReason,
          supportedReasoningEfforts: model.supportedReasoningEfforts || [],
          defaultReasoningEffort: model.defaultReasoningEffort,
          defaultOption: false,
          customOption: false,
        } satisfies IModelOptionItem;
      }),
    );
  } catch {
    // Backend subscription APIs may not be registered yet; keep API-key path intact.
    return [];
  }
};

export const listAvailableModelOptions = async (): Promise<IModelOptionItem[]> => {
  const presetOptions = runtimeEditionConfig.remoteAiModelOptions
    ? (await aiStreamService.getModelOptions(undefined as void)) || []
    : [];

  const subscriptionOptions = await listSubscriptionModelOptions();

  if (!runtimeEditionConfig.localPersistence) {
    const mergedRemote = [...subscriptionOptions, ...presetOptions];
    if (!mergedRemote.some((item) => item.defaultOption) && mergedRemote.length > 0) {
      const firstSelectable = mergedRemote.find((item) => item.selectable !== false) || mergedRemote[0];
      firstSelectable.defaultOption = true;
    }
    return mergedRemote;
  }

  const localOptions = loadLocalConfigs()
    .filter((item) => item.enabled !== false)
    .map(createLocalModelOption);

  // API-key models remain first-class; subscription models are additive and never forced.
  const merged = [...localOptions, ...subscriptionOptions, ...presetOptions];
  if (!merged.some((item) => item.defaultOption) && merged.length > 0) {
    const firstSelectable = merged.find((item) => item.selectable !== false) || merged[0];
    firstSelectable.defaultOption = true;
  }
  return merged;
};

export const resolveModelRequestPayload = async (option: IModelOptionItem, reasoningEffort?: string | null) => {
  if (option.subscriptionOption || option.accessType === 'SUBSCRIPTION') {
    if (option.selectable === false) {
      return null;
    }
    return {
      modelConfigId: undefined,
      modelRefKey: option.modelRefKey || option.value,
      accessType: 'SUBSCRIPTION' as const,
      provider: option.provider,
      model: option.model,
      apiKey: undefined,
      baseUrl: undefined,
      projectId: undefined,
      location: undefined,
      temperature: undefined,
      maxTokens: undefined,
      reasoningEffort: reasoningEffort || undefined,
    };
  }

  if (runtimeEditionConfig.localPersistence && option.customOption && option.modelConfigId) {
    const config = loadLocalConfigs().find((item) => item.id === option.modelConfigId);
    if (!config) {
      return null;
    }
    return {
      modelConfigId: undefined,
      provider: config.provider,
      model: config.model,
      apiKey: config.apiKey?.trim() || undefined,
      baseUrl: config.baseUrl?.trim() || undefined,
      projectId: config.projectId?.trim() || undefined,
      location: config.location?.trim() || undefined,
      temperature: config.temperature,
      maxTokens: config.maxTokens,
    };
  }

  if (option.modelConfigId) {
    return {
      modelConfigId: option.modelConfigId,
      provider: undefined,
      model: option.model,
      apiKey: undefined,
      baseUrl: undefined,
      projectId: undefined,
      location: undefined,
      temperature: undefined,
      maxTokens: undefined,
    };
  }

  return {
    modelConfigId: undefined,
    provider: option.provider,
    model: option.model,
    apiKey: undefined,
    baseUrl: undefined,
    projectId: undefined,
    location: undefined,
    temperature: undefined,
    maxTokens: undefined,
  };
};
