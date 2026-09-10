import type { AIProvider, AgentModelApi } from '@/service/aiModelConfig';

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

export const resolveAgentModelApi = (provider: AIProvider, api?: AgentModelApi, baseUrl?: string): AgentModelApi => {
  if (api) return api;
  if (provider === 'CLAUDE' || provider === 'MINIMAX' && baseUrl?.includes('/anthropic')) return 'anthropic-messages';
  if (provider === 'GEMINI') return 'google-generative-ai';
  if (provider === 'MINIMAX') return 'openai-completions';
  return 'openai-responses';
};
