import type { AIProvider } from '@/service/aiModelConfig';

export const resolveProviderBaseUrl = (provider: AIProvider, baseUrl?: string): string => {
  void provider;
  return baseUrl || '';
};

export const resolveBaseUrlOnProviderChange = (provider: AIProvider, baseUrl?: string): string => {
  void provider;
  return baseUrl || '';
};
