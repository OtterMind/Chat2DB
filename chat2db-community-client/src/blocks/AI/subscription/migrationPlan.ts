import type {
  AiMigrationDefaultDecision,
  AiSecretImportAttemptView,
  AiSecretImportEncryptedEnvelope,
  AiSecretImportItemResult,
  AiSecretImportItemView,
} from '@/typings/aiSubscription';

export interface MigrationDefaultPlan {
  decision: AiMigrationDefaultDecision;
  preselectedModelRefKey: string | null;
  requireUserConfirmation: boolean;
}

export interface MigrationProgress {
  completed: boolean;
  succeededCount: number;
  failedCount: number;
  pendingCount: number;
  /** Local plaintext may be deleted only for succeeded items after backend readback. */
  deletableLocalItemIds: string[];
  /** Failed items keep local plaintext and remain retryable. */
  retryableItemIds: string[];
}

/**
 * Migration never silently changes the global default.
 * - Valid backend default is preserved.
 * - Matching selectedModel + defaultConfig is only a preselection that still needs confirm.
 * - Conflicts / invalid / built-in presets require explicit choice.
 * - Skip leaves no default.
 */
export function resolveMigrationDefaultPlan(params: {
  backendHasValidDefault: boolean;
  backendDefaultModelRefKey?: string | null;
  legacySelectedModelMatchesDefaultConfig: boolean;
  legacyCandidateModelRefKey?: string | null;
  legacyCandidateValid: boolean;
  sourcesConflict: boolean;
  referencesBuiltInPreset: boolean;
  userSkipped: boolean;
}): MigrationDefaultPlan {
  if (params.userSkipped) {
    return {
      decision: 'NO_DEFAULT',
      preselectedModelRefKey: null,
      requireUserConfirmation: false,
    };
  }

  if (params.backendHasValidDefault) {
    return {
      decision: 'KEEP_BACKEND_DEFAULT',
      preselectedModelRefKey: params.backendDefaultModelRefKey || null,
      requireUserConfirmation: false,
    };
  }

  if (params.sourcesConflict || params.referencesBuiltInPreset || !params.legacyCandidateValid) {
    return {
      decision: 'REQUIRE_EXPLICIT_CHOICE',
      preselectedModelRefKey: null,
      requireUserConfirmation: true,
    };
  }

  if (params.legacySelectedModelMatchesDefaultConfig && params.legacyCandidateModelRefKey) {
    return {
      decision: 'PRESELECT_LEGACY_CANDIDATE',
      preselectedModelRefKey: params.legacyCandidateModelRefKey,
      requireUserConfirmation: true,
    };
  }

  return {
    decision: 'REQUIRE_EXPLICIT_CHOICE',
    preselectedModelRefKey: null,
    requireUserConfirmation: true,
  };
}

export function summarizeMigrationProgress(
  attempt: AiSecretImportAttemptView,
  latestResults: readonly AiSecretImportItemResult[] = [],
): MigrationProgress {
  const resultById = new Map(latestResults.map((item) => [item.itemId, item]));
  let succeededCount = 0;
  let failedCount = 0;
  let pendingCount = 0;
  const deletableLocalItemIds: string[] = [];
  const retryableItemIds: string[] = [];

  for (const item of attempt.items) {
    const result = resultById.get(item.itemId);
    const status = result ? (result.success ? 'SUCCEEDED' : 'FAILED') : item.status;

    if (status === 'SUCCEEDED') {
      succeededCount += 1;
      deletableLocalItemIds.push(item.itemId);
      continue;
    }
    if (status === 'FAILED') {
      failedCount += 1;
      if (result?.errorCode !== 'IMPORT_OUTCOME_UNKNOWN') {
        retryableItemIds.push(item.itemId);
      }
      continue;
    }
    if (status === 'SKIPPED') {
      continue;
    }
    pendingCount += 1;
  }

  const completed = attempt.completed || (pendingCount === 0 && failedCount === 0);
  return {
    completed: completed && failedCount === 0,
    succeededCount,
    failedCount,
    pendingCount,
    deletableLocalItemIds,
    retryableItemIds,
  };
}

/**
 * Typed envelope builder for the dedicated secret-import channel.
 * Callers supply already-encrypted ciphertext; plaintext keys never enter this helper.
 */
export function buildEncryptedImportEnvelope(params: {
  attemptId: string;
  itemId: string;
  nonceBase64: string;
  wrappedAesKeyBase64: string;
  ciphertextBase64: string;
  expiresAtEpochMs: number;
  confirmDefault?: boolean;
}): AiSecretImportEncryptedEnvelope {
  if (!params.attemptId.trim() || !params.itemId.trim()) {
    throw new Error('attemptId and itemId are required');
  }
  if (!params.nonceBase64 || !params.wrappedAesKeyBase64 || !params.ciphertextBase64) {
    throw new Error('encrypted envelope fields are required');
  }
  return {
    schemaVersion: 1,
    attemptId: params.attemptId,
    itemId: params.itemId,
    nonceBase64: params.nonceBase64,
    wrappedKeyBase64: params.wrappedAesKeyBase64,
    ciphertextBase64: params.ciphertextBase64,
    expiresAtEpochMs: params.expiresAtEpochMs,
    confirmDefault: !!params.confirmDefault,
  };
}

/** Resolve an explicit user decision without ever inferring a new backend default. */
export function resolveConfirmedDefaultItemId(params: {
  backendHasValidDefault: boolean;
  itemIds: readonly string[];
  selectedItemId: string | null | undefined;
}): string | null {
  if (params.backendHasValidDefault) return null;
  if (params.selectedItemId === undefined) {
    throw new Error('MIGRATION_DEFAULT_CONFIRM_REQUIRED');
  }
  if (params.selectedItemId === null) return null;
  if (!params.itemIds.includes(params.selectedItemId)) {
    throw new Error('MIGRATION_DEFAULT_ITEM_INVALID');
  }
  return params.selectedItemId;
}

export function listVisibleMigrationItems(items: readonly AiSecretImportItemView[]): Array<{
  itemId: string;
  configName: string;
  provider: string;
  status: AiSecretImportItemView['status'];
  errorCode?: string | null;
}> {
  // UI, errors, and logs must never include key material — only names/providers/status.
  return items.map((item) => ({
    itemId: item.itemId,
    configName: item.configName,
    provider: item.provider,
    status: item.status,
    errorCode: item.errorCode ?? null,
  }));
}
