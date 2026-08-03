/**
 * Renderer contracts for Community packaged-JCEF subscription AI.
 * Mirror backend domain types without OAuth tokens, API keys, or secret material.
 */

export type AiAccessType = 'API_KEY' | 'SUBSCRIPTION';

export type AiProviderId = 'OPENAI' | 'XAI' | 'CLAUDE' | 'GEMINI';

export type AiRouteKind = 'SPRING_AI_API_KEY' | 'CHATGPT_CODEX_APP_SERVER';

export type AiProviderConnectionState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'DISCOVERY_FAILED'
  | 'DISCONNECTING'
  | 'DISCONNECT_FAILED'
  | 'DISABLED';

export type AiSubscriptionDisabledReason =
  | 'NONE'
  | 'FEATURE_DISABLED'
  | 'NOT_COMMUNITY_RUNTIME'
  | 'NOT_DESKTOP'
  | 'GUI_DISABLED'
  | 'NOT_PACKAGED_RELEASE'
  | 'KEYRING_UNAVAILABLE'
  | 'APP_SERVER_UNAVAILABLE';

export type AiAttemptState =
  | 'CREATED'
  | 'SUBMITTING'
  | 'ACTIVE'
  | 'TOOL_ACTIVE'
  | 'OUTPUT_VISIBLE'
  | 'COMPLETED'
  | 'FAILED'
  | 'INTERRUPTED'
  | 'OUTCOME_UNKNOWN'
  | 'TOOL_OUTCOME_UNKNOWN';

export type AiToolExecutionState = 'STARTED' | 'COMPLETED' | 'OUTCOME_UNKNOWN';

/** Backend-issued opaque model identity. No secrets. */
export interface AiModelRef {
  accessType: AiAccessType;
  provider: AiProviderId;
  routeKind: AiRouteKind;
  modelId: string;
}

export interface AiSubscriptionCapability {
  enabled: boolean;
  disabledReason: AiSubscriptionDisabledReason;
}

export interface AiProviderConnectionView {
  provider: AiProviderId;
  /** Display name for settings, e.g. "ChatGPT". */
  displayName: string;
  state: AiProviderConnectionState;
  /** Masked account only (email/handle), never tokens. */
  maskedAccount?: string | null;
  fenceGeneration: number;
  discoveredAt?: string | null;
  discoveryErrorCode?: string | null;
  /** True when refresh/auth failed and the user must re-authorize. */
  reauthRequired?: boolean;
  disabledReason?: AiSubscriptionDisabledReason | null;
  /** Eligible for login UI; false for SuperGrok waitlist / non-eligible providers. */
  eligible: boolean;
  /** Whether a full account-management entry is shown in settings. */
  showAccountManagement: boolean;
}

export interface AiModelSnapshotView {
  modelRef: AiModelRef;
  /** Stable selector value issued/cached by renderer from modelRef. */
  modelRefKey: string;
  displayName: string;
  discoveredAt: string;
  available: boolean;
  disabledReason?: string | null;
  /** Ordered capabilities confirmed by the authenticated provider catalog. */
  supportedReasoningEfforts?: string[];
  defaultReasoningEffort?: string | null;
  /** Provider plan metadata for display only. */
  planType?: string | null;
}

export interface AiAttemptView {
  /** Backend-issued attempt id only. */
  attemptId: string;
  messageId: string;
  provider: AiProviderId;
  state: AiAttemptState;
  modelRefKey?: string | null;
  modelDisplayName?: string | null;
  partialOutput?: string | null;
  toolStarted: boolean;
  toolOutcomeUnknown: boolean;
  errorCode?: string | null;
  quotaResetAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AiProviderBusyError {
  code: 'PROVIDER_BUSY';
  provider: AiProviderId;
  activeAttemptId?: string | null;
}

export type AiSendBlockReason =
  | 'SURFACE_DISABLED'
  | 'PROVIDER_DISABLED'
  | 'NOT_CONNECTED'
  | 'CONNECTING'
  | 'DISCOVERY_FAILED'
  | 'DISCONNECTING'
  | 'DISCONNECT_FAILED'
  | 'REQUIRES_REAUTH'
  | 'NO_AVAILABLE_MODEL'
  | 'MODEL_STALE_OR_DISABLED'
  | 'PROVIDER_BUSY'
  | 'LEGACY_MODEL_UNCONFIRMED'
  | 'NONE';

export interface AiMessageModelSnapshot {
  messageId: string;
  modelRefKey?: string | null;
  modelDisplayName?: string | null;
  /** Pre-upgrade messages without snapshot stay legacy/unknown. */
  legacyUnknown: boolean;
}

/** Migration item identity only — never includes API key material. */
export interface AiSecretImportItemView {
  itemId: string;
  configName: string;
  provider: string;
  status: 'PENDING' | 'IMPORTING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
  errorCode?: string | null;
}

export interface AiSecretImportAttemptView {
  attemptId: string;
  items: AiSecretImportItemView[];
  completed: boolean;
  /** RSA-OAEP public key (SPKI base64) for envelope wrapping. Ephemeral. */
  publicKeySpkiBase64?: string | null;
  expiresAtEpochMs?: number | null;
  schemaVersion?: 1;
}

/**
 * Encrypted migration envelope body. The AES-wrapped payload is opaque ciphertext.
 * Renderer state must never hold plaintext keys after handoff.
 */
export interface AiSecretImportEncryptedEnvelope {
  schemaVersion: 1;
  attemptId: string;
  itemId: string;
  nonceBase64: string;
  wrappedKeyBase64: string;
  ciphertextBase64: string;
  expiresAtEpochMs: number;
  confirmDefault: boolean;
}

export interface AiSecretImportItemResult {
  itemId: string;
  success: boolean;
  status?: 'SUCCEEDED' | 'ALREADY_IMPORTED' | 'FAILED';
  errorCode?: string | null;
}

export type AiMigrationDefaultDecision =
  | 'KEEP_BACKEND_DEFAULT'
  | 'PRESELECT_LEGACY_CANDIDATE'
  | 'REQUIRE_EXPLICIT_CHOICE'
  | 'NO_DEFAULT';

export interface AiModelPreferenceView {
  globalDefaultModelRefKey?: string | null;
  conversationModelRefKey?: string | null;
}
