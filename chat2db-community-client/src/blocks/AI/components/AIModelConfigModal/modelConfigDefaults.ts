import type { AIProvider } from '@/service/aiModelConfig';

export const DEFAULT_MINIMAX_BASE_URL = 'https://api.minimax.io/v1';
// LiteLLM is self-hosted; default to the proxy's conventional local address.
export const DEFAULT_LITELLM_BASE_URL = 'http://localhost:4000/v1';

const PROVIDER_DEFAULT_BASE_URLS: Partial<Record<AIProvider, string>> = {
  MINIMAX: DEFAULT_MINIMAX_BASE_URL,
  LITELLM: DEFAULT_LITELLM_BASE_URL,
};

export const resolveProviderBaseUrl = (provider: AIProvider, baseUrl?: string): string => {
  const providerDefault = PROVIDER_DEFAULT_BASE_URLS[provider];
  if (providerDefault && !baseUrl?.trim()) {
    return providerDefault;
  }
  return baseUrl || '';
};

export const resolveBaseUrlOnProviderChange = (provider: AIProvider, baseUrl?: string): string => {
  if (PROVIDER_DEFAULT_BASE_URLS[provider]) {
    return resolveProviderBaseUrl(provider, baseUrl);
  }
  // Clear a previously auto-filled provider default when switching to a provider
  // that has none, so a stale default URL is not carried over.
  if (baseUrl && Object.values(PROVIDER_DEFAULT_BASE_URLS).includes(baseUrl.trim())) {
    return '';
  }
  return baseUrl || '';
};
