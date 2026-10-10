import type { AIProvider } from '@/service/aiModelConfig';

export const MINIMAX_BASE_URL_PRESETS: Array<{ label: string; value: string }> = [
  { label: 'Global (OpenAI compatible)', value: 'https://api.minimax.io/v1' },
  { label: 'China (OpenAI compatible)', value: 'https://api.minimaxi.com/v1' },
  { label: 'Global (Anthropic compatible)', value: 'https://api.minimax.io/anthropic' },
  { label: 'China (Anthropic compatible)', value: 'https://api.minimaxi.com/anthropic' },
];

export const DEFAULT_MINIMAX_BASE_URL = MINIMAX_BASE_URL_PRESETS[0].value;

const MINIMAX_BASE_URLS = new Set(MINIMAX_BASE_URL_PRESETS.map(({ value }) => value));

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
  if (baseUrl && MINIMAX_BASE_URLS.has(baseUrl.trim())) {
    return '';
  }
  return baseUrl || '';
};
