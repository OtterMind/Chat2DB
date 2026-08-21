import { clientRuntime } from '@client-runtime';
import aiStreamService, { IModelOptionItem } from './aiStream';
import createRequest from './base';

export type AIProvider = 'OPENAI' | 'CLAUDE' | 'GEMINI' | 'MINIMAX';

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

const LOCAL_STORAGE_KEY = clientRuntime.aiModelConfigStorageKey;

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
  if (clientRuntime.usesLocalPersistence) {
    return loadLocalConfigs();
  }
  return (await listRemoteModelConfigs(undefined as void)) || [];
};

export const saveAIModelConfig = async (payload: IAIModelConfigSaveRequest) => {
  if (!clientRuntime.usesLocalPersistence) {
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
  if (!clientRuntime.usesLocalPersistence) {
    await deleteRemoteModelConfig({ id });
    return;
  }

  const current = loadLocalConfigs().filter((item) => item.id !== id);
  persistLocalConfigs(ensureOneDefaultConfig(current));
};

export const testAIModelConfig = async (payload: IAIModelConfigSaveRequest) => {
  const nextPayload = { ...payload };
  if (clientRuntime.usesLocalPersistence && !nextPayload.apiKey?.trim() && nextPayload.id) {
    const localConfig = loadLocalConfigs().find((item) => item.id === nextPayload.id);
    if (localConfig?.apiKey?.trim()) {
      nextPayload.apiKey = localConfig.apiKey.trim();
    }
  }
  return testRemoteModelConfig(nextPayload);
};

export const listAvailableModelOptions = async (): Promise<IModelOptionItem[]> => {
  const presetOptions = clientRuntime.loadModelOptionsFromServer
    ? (await aiStreamService.getModelOptions(undefined as void)) || []
    : [];

  if (!clientRuntime.usesLocalPersistence) {
    return presetOptions;
  }

  const localOptions = loadLocalConfigs()
    .filter((item) => item.enabled !== false)
    .map(createLocalModelOption);

  const merged = [...localOptions, ...presetOptions];
  if (!merged.some((item) => item.defaultOption) && merged.length > 0) {
    merged[0].defaultOption = true;
  }
  return merged;
};

export const resolveModelRequestPayload = async (option: IModelOptionItem) => {
  if (clientRuntime.usesLocalPersistence && option.customOption && option.modelConfigId) {
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
