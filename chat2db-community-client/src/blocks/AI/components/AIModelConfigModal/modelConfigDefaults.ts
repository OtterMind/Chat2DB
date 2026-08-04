import type { AIProvider } from '@/service/aiModelConfig';

export const DEFAULT_MINIMAX_BASE_URL = 'https://api.minimax.io/v1';

export const resolveProviderBaseUrl = (provider: AIProvider, baseUrl?: string): string => {
  if (provider === 'MINIMAX' && !baseUrl?.trim()) {
    return DEFAULT_MINIMAX_BASE_URL;
  }
  return baseUrl || '';
};

export const resolveBaseUrlOnProviderChange = (provider: AIProvider, baseUrl?: string): string => {
  if (provider === 'MINIMAX') {
    return resolveProviderBaseUrl(provider, baseUrl);
  }
  if (baseUrl?.trim() === DEFAULT_MINIMAX_BASE_URL) {
    return '';
  }
  return baseUrl || '';
};
