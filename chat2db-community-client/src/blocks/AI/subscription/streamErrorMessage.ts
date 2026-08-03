/**
 * Maps subscription stream error codes to i18n keys / fallback text.
 * Machine codes stay on the wire; the chat surface must never show only raw codes.
 */

const STREAM_ERROR_I18N: Record<string, string> = {
  TURN_IDLE_TIMEOUT_OUTCOME_UNKNOWN: 'ai.subscription.stream.idleTimeout',
  TURN_TIMEOUT_OUTCOME_UNKNOWN: 'ai.subscription.stream.turnTimeout',
  TURN_OUTCOME_UNKNOWN: 'ai.subscription.stream.outcomeUnknown',
  TOOL_OUTCOME_UNKNOWN: 'ai.subscription.stream.toolOutcomeUnknown',
  PROVIDER_BUSY: 'ai.subscription.attempt.providerBusy',
  APP_SERVER_TOOL_NOT_ALLOWED: 'ai.subscription.stream.toolNotAllowed',
  APP_SERVER_CODE_MODE_NOT_ALLOWED: 'ai.subscription.stream.codeModeNotAllowed',
  APP_SERVER_MCP_STALLED: 'ai.subscription.stream.mcpStalled',
  MODEL_REJECTED: 'ai.subscription.stream.modelRejected',
  SUBSCRIPTION_NOT_CONNECTED: 'ai.subscription.stream.notConnected',
  MODEL_NOT_RECENTLY_CONFIRMED: 'ai.subscription.stream.modelStale',
};

export function streamErrorI18nKey(errorCode: string | undefined | null): string | null {
  if (!errorCode || typeof errorCode !== 'string') {
    return null;
  }
  return STREAM_ERROR_I18N[errorCode] || null;
}

export function resolveStreamErrorDisplay(params: {
  errorCode?: string | null;
  content?: string | null;
  t: (key: string, fallback?: string) => string;
}): string {
  const key = streamErrorI18nKey(params.errorCode);
  if (key) {
    return params.t(key);
  }
  if (params.content && params.content.trim() && params.content !== params.errorCode) {
    return params.content;
  }
  if (params.errorCode) {
    return params.t('ai.subscription.stream.generic', params.errorCode);
  }
  return params.t('ai.subscription.stream.generic', 'AI stream error');
}
