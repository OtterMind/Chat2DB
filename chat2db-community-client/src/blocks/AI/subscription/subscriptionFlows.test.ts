import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import type {
  AiAttemptView,
  AiModelSnapshotView,
  AiProviderConnectionView,
  AiSecretImportAttemptView,
} from '../../../typings/aiSubscription';
import {
  isSubscriptionAiSurfaceAvailable,
  resolveSurfaceDisabledReason,
} from './capability';
import * as capabilityUxModule from './capability';
import {
  listManageableProviders,
  listQuickConnectProviders,
  presentAccountState,
  resolveAccountUserFacingState,
} from './accountState';
import {
  createChatGptSubscriptionModelRef,
  isValidChatGptSubscriptionModelRef,
  parseModelRefKey,
  toModelRefKey,
} from './modelRef';
import {
  groupSubscriptionModels,
  presentModelSnapshot,
  resolvePostDiscoverySelection,
} from './modelSnapshot';
import * as modelSnapshotModule from './modelSnapshot';
import {
  decideManualRetry,
  mapProviderBusyToSendBlock,
  presentAttempt,
  providerBusyMessageI18nKey,
  toolRetryWarningI18nKey,
} from './attemptUi';
import { resolveStreamErrorDisplay } from './streamErrorMessage';
import {
  applyConversationModelChoice,
  conversationRequiresLegacyModelConfirm,
  presentLegacyMessage,
  resolveLegacySendGate,
} from './legacyHistory';
import {
  buildEncryptedImportEnvelope,
  listVisibleMigrationItems,
  resolveConfirmedDefaultItemId,
  resolveMigrationDefaultPlan,
  summarizeMigrationProgress,
} from './migrationPlan';
import { evaluateSubscriptionSendGate } from './sendGate';
import { evaluateChatSendGuard } from './chatGuards';
import {
  buildModelSelectSections,
  decideSubscriptionModelRefresh,
  isSubscriptionConnectOption,
  subscriptionConnectOptionValue,
} from './modelSelectGroups';
import * as modelSelectUxModule from './modelSelectGroups';
import { hydrateSubscriptionModelOptions } from './startupHydration';
import { resolveTerminalAnswerFallback } from './terminalAnswer';

const chatGptDisconnected: AiProviderConnectionView = {
  provider: 'OPENAI',
  displayName: 'ChatGPT',
  state: 'DISCONNECTED',
  fenceGeneration: 0,
  eligible: true,
  showAccountManagement: true,
};

const superGrokWaitlist: AiProviderConnectionView = {
  provider: 'XAI',
  displayName: 'SuperGrok',
  state: 'DISABLED',
  fenceGeneration: 0,
  eligible: false,
  showAccountManagement: false,
  disabledReason: 'FEATURE_DISABLED',
};

const modelRef = createChatGptSubscriptionModelRef('gpt-5');
const modelRefKey = toModelRefKey(modelRef);

const availableSnapshot: AiModelSnapshotView = {
  modelRef,
  modelRefKey,
  displayName: 'GPT-5',
  discoveredAt: '2026-07-31T10:00:00.000Z',
  available: true,
  supportedReasoningEfforts: ['low', 'high', 'xhigh'],
  defaultReasoningEffort: 'medium',
};

// --- subscription stream errors and reasoning effort ---
const sseErrorPayloadSource = readFileSync(
  new URL('../../../components/SSERequest/errorPayload.ts', import.meta.url),
  'utf8',
);
assert.equal(
  sseErrorPayloadSource.includes('export const isApplicationStreamErrorPayload'),
  true,
  'application stream errors have a dedicated classifier and are not swallowed as generic API errors',
);

const sseClientSource = readFileSync(
  new URL('../../../components/SSERequest/sseClientRequest.ts', import.meta.url),
  'utf8',
);
assert.equal(
  sseClientSource.includes('isApplicationStreamErrorPayload(sseOutput?.data)'),
  true,
  'desktop SSE client classifies in-band application stream errors',
);
assert.equal(
  sseClientSource.includes('Keep the listener open for the following done payload'),
  true,
  'application stream errors must not complete the SSE client before done carries finalAnswer',
);
const appErrorBranch = sseClientSource.slice(
  sseClientSource.indexOf('isApplicationStreamErrorPayload(sseOutput?.data)'),
  sseClientSource.indexOf('} else if (handleSSEErrorPayload'),
);
assert.equal(
  appErrorBranch.includes('callbacks.onUpdate(sseOutput as Output)'),
  true,
  'application stream errors still update the chat renderer',
);
assert.equal(
  appErrorBranch.includes('onSuccess') || appErrorBranch.includes('this.stop()'),
  false,
  'application stream errors no longer call onSuccess/stop before done',
);

assert.equal(
  resolveStreamErrorDisplay({
    errorCode: 'TURN_IDLE_TIMEOUT_OUTCOME_UNKNOWN',
    content: 'TURN_IDLE_TIMEOUT_OUTCOME_UNKNOWN',
    t: (key) => (key === 'ai.subscription.stream.idleTimeout' ? 'idle-friendly' : key),
  }),
  'idle-friendly',
  'idle timeout errors map to a user-facing message instead of the raw code',
);
assert.equal(
  resolveStreamErrorDisplay({
    errorCode: 'APP_SERVER_CODE_MODE_NOT_ALLOWED',
    content: 'APP_SERVER_CODE_MODE_NOT_ALLOWED',
    t: (key) => (key === 'ai.subscription.stream.codeModeNotAllowed' ? 'no-code-mode' : key),
  }),
  'no-code-mode',
  'code-mode rejections map to a user-facing message',
);

const modelSnapshotExports = modelSnapshotModule as unknown as Record<string, unknown>;
assert.equal(
  typeof modelSnapshotExports.resolveReasoningEffortSelection,
  'function',
  'subscription model snapshots expose a deterministic reasoning-effort selection',
);
const resolveReasoningEffortSelection = modelSnapshotExports.resolveReasoningEffortSelection as (params: {
  supportedReasoningEfforts: string[];
  defaultReasoningEffort?: string | null;
  previousReasoningEffort?: string | null;
}) => { value: string | null; options: string[] };
assert.deepEqual(
  resolveReasoningEffortSelection({
    supportedReasoningEfforts: ['low', 'high', 'xhigh'],
    defaultReasoningEffort: 'medium',
  }),
  { value: 'high', options: ['low', 'high', 'xhigh'] },
  'High is the product default when the selected model advertises it',
);
assert.deepEqual(
  resolveReasoningEffortSelection({
    supportedReasoningEfforts: ['low', 'medium'],
    defaultReasoningEffort: 'medium',
  }),
  { value: 'medium', options: ['low', 'medium'] },
  'provider default is used when High is unavailable',
);
assert.deepEqual(
  resolveReasoningEffortSelection({
    supportedReasoningEfforts: ['low', 'high'],
    defaultReasoningEffort: 'low',
    previousReasoningEffort: 'low',
  }),
  { value: 'low', options: ['low', 'high'] },
  'an existing explicit selection remains stable while supported',
);

const useSseRequestSource = readFileSync(new URL('../../../hooks/useSSERequest.ts', import.meta.url), 'utf8');
assert.equal(
  /console\.(?:log|debug)\([^\n]*(?:params|chunk|parsedData)/.test(useSseRequestSource),
  false,
  'the shared stream hook must not print prompts, SQL, attachments, or response chunks',
);

// --- terminal answer recovery across independently scheduled JCEF pushes ---
assert.equal(
  resolveTerminalAnswerFallback('', '\u6700\u7ec8\u56de\u7b54'),
  '\u6700\u7ec8\u56de\u7b54',
  'a terminal event restores the complete answer when earlier JCEF deltas were not observed',
);
assert.equal(
  resolveTerminalAnswerFallback('\u6700\u7ec8', '\u6700\u7ec8\u56de\u7b54'),
  '\u56de\u7b54',
  'a terminal event appends only the missing suffix after partial streaming',
);
assert.equal(
  resolveTerminalAnswerFallback('\u5df2\u5b8c\u6210', '\u5df2\u5b8c\u6210'),
  '',
  'a terminal event never duplicates an answer already assembled from deltas',
);
assert.equal(
  resolveTerminalAnswerFallback('\u672c\u5730\u5185\u5bb9', '\u4e0d\u4e00\u81f4\u5185\u5bb9'),
  '',
  'a conflicting terminal payload never overwrites text already shown to the user',
);

// --- capability / surface gate ---
assert.equal(
  isSubscriptionAiSurfaceAvailable({ communityRuntime: true, packagedJcefDesktop: true }),
  true,
  'packaged Community JCEF can show subscription UI before backend capability loads',
);
assert.equal(
  isSubscriptionAiSurfaceAvailable({ communityRuntime: false, packagedJcefDesktop: true }),
  false,
  'non-Community runtimes hide subscription UI',
);
assert.equal(
  isSubscriptionAiSurfaceAvailable({ communityRuntime: true, packagedJcefDesktop: false }),
  false,
  'browser/Docker without JCEF hide subscription UI',
);
assert.equal(
  isSubscriptionAiSurfaceAvailable({
    communityRuntime: true,
    packagedJcefDesktop: true,
    backendCapability: { enabled: false, disabledReason: 'KEYRING_UNAVAILABLE' },
  }),
  false,
  'backend-disabled capability hides subscription UI',
);
assert.equal(
  resolveSurfaceDisabledReason({ communityRuntime: true, packagedJcefDesktop: false }),
  'NOT_DESKTOP',
);

const capabilityUx = capabilityUxModule as unknown as Record<string, unknown>;
assert.equal(
  typeof capabilityUx.isSubscriptionManagementEntryVisible,
  'function',
  'subscription settings visibility has a dedicated recoverable-error decision',
);
const isSubscriptionManagementEntryVisible = capabilityUx.isSubscriptionManagementEntryVisible as (params: {
  communityRuntime: boolean;
  packagedJcefDesktop: boolean;
  hydrated: boolean;
  backendCapability: { enabled: boolean; disabledReason: string } | null;
  lastErrorCode: string | null;
}) => boolean;
assert.equal(
  isSubscriptionManagementEntryVisible({
    communityRuntime: true,
    packagedJcefDesktop: true,
    hydrated: true,
    backendCapability: { enabled: false, disabledReason: 'APP_SERVER_UNAVAILABLE' },
    lastErrorCode: 'CAPABILITY_FETCH_FAILED',
  }),
  true,
  'recoverable capability failures keep the management entry visible for retry',
);
assert.equal(
  isSubscriptionManagementEntryVisible({
    communityRuntime: true,
    packagedJcefDesktop: true,
    hydrated: true,
    backendCapability: { enabled: false, disabledReason: 'FEATURE_DISABLED' },
    lastErrorCode: null,
  }),
  false,
  'an explicit default-off feature gate remains hidden',
);
assert.equal(
  isSubscriptionManagementEntryVisible({
    communityRuntime: true,
    packagedJcefDesktop: true,
    hydrated: true,
    backendCapability: { enabled: false, disabledReason: 'KEYRING_UNAVAILABLE' },
    lastErrorCode: null,
  }),
  true,
  'an unavailable keyring remains visible as an actionable runtime error',
);
assert.equal(
  isSubscriptionManagementEntryVisible({
    communityRuntime: true,
    packagedJcefDesktop: false,
    hydrated: true,
    backendCapability: null,
    lastErrorCode: 'CAPABILITY_FETCH_FAILED',
  }),
  false,
  'browser surfaces never gain the management entry through an error fallback',
);

assert.equal(
  typeof capabilityUx.subscriptionRuntimeErrorI18nKey,
  'function',
  'runtime error codes are converted to safe localized copy keys',
);
const subscriptionRuntimeErrorI18nKey = capabilityUx.subscriptionRuntimeErrorI18nKey as (
  errorCode: string | null,
) => string | null;
assert.equal(
  subscriptionRuntimeErrorI18nKey('CAPABILITY_FETCH_FAILED'),
  'ai.subscription.error.capabilityFetch',
);
assert.equal(
  subscriptionRuntimeErrorI18nKey('PROVIDER_FETCH_FAILED'),
  'ai.subscription.error.providerFetch',
);
assert.equal(
  subscriptionRuntimeErrorI18nKey('KEYRING_UNAVAILABLE'),
  'ai.subscription.error.keyringUnavailable',
);
assert.equal(
  subscriptionRuntimeErrorI18nKey('APP_SERVER_UNAVAILABLE'),
  'ai.subscription.error.appServerUnavailable',
);
assert.equal(subscriptionRuntimeErrorI18nKey('UNRECOGNIZED_INTERNAL_DETAIL'), 'ai.subscription.error.generic');

// --- modelRef encoding ---
assert.equal(parseModelRefKey(modelRefKey)?.modelId, 'gpt-5');
assert.equal(isValidChatGptSubscriptionModelRef(modelRef), true);
assert.equal(
  isValidChatGptSubscriptionModelRef({
    accessType: 'SUBSCRIPTION',
    provider: 'XAI',
    routeKind: 'CHATGPT_CODEX_APP_SERVER',
    modelId: 'grok',
  }),
  false,
  'SuperGrok subscription route is not a valid ChatGPT modelRef',
);

// --- account states ---
assert.equal(resolveAccountUserFacingState(chatGptDisconnected), 'disconnected');
assert.equal(presentAccountState(chatGptDisconnected).canStartConnect, true);
assert.equal(presentAccountState(chatGptDisconnected).showQuickConnect, true);
assert.equal(presentAccountState(chatGptDisconnected).canSendWithSubscriptionModels, false);

const connecting = { ...chatGptDisconnected, state: 'CONNECTING' as const };
assert.equal(presentAccountState(connecting).canCancelConnect, true);
assert.equal(presentAccountState(connecting).sendBlockReason, 'CONNECTING');

const discovering = { ...chatGptDisconnected, state: 'CONNECTED' as const, discoveredAt: null };
assert.equal(resolveAccountUserFacingState(discovering), 'connected_discovering');
assert.equal(presentAccountState(discovering).canSendWithSubscriptionModels, false);

const discoveryFailed = {
  ...chatGptDisconnected,
  state: 'DISCOVERY_FAILED' as const,
  maskedAccount: 'u***@example.com',
};
assert.equal(resolveAccountUserFacingState(discoveryFailed), 'connected_discovery_failed');
assert.equal(presentAccountState(discoveryFailed).canRetryDiscovery, true);
assert.equal(presentAccountState(discoveryFailed).canDisconnect, true);
assert.equal(presentAccountState(discoveryFailed).canSendWithSubscriptionModels, false);

const connected = {
  ...chatGptDisconnected,
  state: 'CONNECTED' as const,
  discoveredAt: '2026-07-31T10:00:00.000Z',
  maskedAccount: 'u***@example.com',
};
assert.equal(presentAccountState(connected).canSendWithSubscriptionModels, true);
assert.equal(presentAccountState(connected).canDisconnect, true);

const reauth = { ...connected, reauthRequired: true };
assert.equal(resolveAccountUserFacingState(reauth), 'requires_reauth');
assert.equal(presentAccountState(reauth).sendBlockReason, 'REQUIRES_REAUTH');
assert.equal(presentAccountState(reauth).canSendWithSubscriptionModels, false);

const disconnecting = { ...connected, state: 'DISCONNECTING' as const };
assert.equal(presentAccountState(disconnecting).sendBlockReason, 'DISCONNECTING');
assert.equal(presentAccountState(disconnecting).canStartConnect, false);

const disconnectFailed = { ...connected, state: 'DISCONNECT_FAILED' as const };
assert.equal(presentAccountState(disconnectFailed).canRetryDisconnect, true);
assert.equal(presentAccountState(disconnectFailed).canSendWithSubscriptionModels, false);

assert.deepEqual(
  listManageableProviders([chatGptDisconnected, superGrokWaitlist]).map((item) => item.provider),
  ['OPENAI'],
  'only eligible providers appear in account management',
);
assert.equal(listQuickConnectProviders([superGrokWaitlist]).length, 0, 'SuperGrok is not a login button');
assert.equal(listQuickConnectProviders([chatGptDisconnected]).length, 1);

// --- model snapshot wording and staleness ---
const selectable = presentModelSnapshot(availableSnapshot, connected);
assert.equal(selectable.selectable, true);
assert.equal(selectable.wordingI18nKey, 'ai.subscription.model.recentlyConfirmed');

const stale = presentModelSnapshot(
  { ...availableSnapshot, available: false, disabledReason: 'STALE' },
  connected,
);
assert.equal(stale.selectable, false);
assert.equal(stale.disabledI18nKey, 'ai.subscription.model.lastAvailable');

const rejected = presentModelSnapshot(
  { ...availableSnapshot, available: false, disabledReason: 'REJECTED' },
  connected,
);
assert.equal(rejected.disabledI18nKey, 'ai.subscription.model.rejected');

const afterDiscoveryFailure = presentModelSnapshot(availableSnapshot, discoveryFailed);
assert.equal(afterDiscoveryFailure.selectable, false);
assert.equal(afterDiscoveryFailure.disabledI18nKey, 'ai.subscription.model.temporarilyUnavailable');

const groups = groupSubscriptionModels(
  [
    availableSnapshot,
    {
      ...availableSnapshot,
      modelRef: createChatGptSubscriptionModelRef('o3'),
      modelRefKey: toModelRefKey(createChatGptSubscriptionModelRef('o3')),
      displayName: 'o3',
      discoveredAt: '2026-07-31T11:00:00.000Z',
    },
  ],
  [connected],
);
assert.equal(groups.length, 1);
assert.equal(groups[0].snapshotUpdatedAt, '2026-07-31T11:00:00.000Z');
assert.equal(groups[0].availabilityWordingI18nKey, 'ai.subscription.model.recentlyConfirmed');
assert.equal(groups[0].models.every((item) => item.selectable), true);

assert.deepEqual(
  decideSubscriptionModelRefresh({
    previousSnapshots: [{ ...availableSnapshot, available: false, disabledReason: 'STALE' }],
    currentSnapshots: [availableSnapshot],
    postLoginGuidePending: false,
  }),
  { reloadModelOptions: true, showPostLoginGuide: false },
  'a normal startup/dropdown refresh rebuilds model options when the recovered catalog becomes selectable',
);
assert.deepEqual(
  decideSubscriptionModelRefresh({
    previousSnapshots: [],
    currentSnapshots: [availableSnapshot],
    postLoginGuidePending: true,
  }),
  { reloadModelOptions: true, showPostLoginGuide: true },
  'a user-initiated login keeps the selector guidance while sharing the same reload path',
);

const postDiscovery = resolvePostDiscoverySelection({
  previousSelectedModelRefKey: null,
  validGlobalDefaultModelRefKey: modelRefKey,
  availableModelRefKeys: [modelRefKey],
});
assert.equal(postDiscovery.requireExplicitChoice, true);
assert.equal(postDiscovery.selectedModelRefKey, null, 'post-login does not auto-select or overwrite default');

// --- attempts: busy / unknown / tool warning ---
const failedNoTool: AiAttemptView = {
  attemptId: 'att-1',
  messageId: 'msg-1',
  provider: 'OPENAI',
  state: 'FAILED',
  toolStarted: false,
  toolOutcomeUnknown: false,
  partialOutput: 'partial text',
  createdAt: '2026-07-31T10:00:00.000Z',
  updatedAt: '2026-07-31T10:00:01.000Z',
};
const failedPresentation = presentAttempt(failedNoTool);
assert.equal(failedPresentation.showPartialOutput, true);
assert.equal(failedPresentation.includePartialInFutureContext, false);
assert.equal(failedPresentation.canManualRetry, true);
assert.equal(decideManualRetry(failedNoTool).requiresDuplicateToolWarning, false);
assert.equal(decideManualRetry(failedNoTool).createsNewAttempt, true);

const failedWithTool: AiAttemptView = { ...failedNoTool, attemptId: 'att-2', toolStarted: true };
assert.equal(decideManualRetry(failedWithTool).requiresDuplicateToolWarning, true);
assert.equal(toolRetryWarningI18nKey(), 'ai.subscription.attempt.toolRetryWarning');

const outcomeUnknown: AiAttemptView = {
  ...failedNoTool,
  attemptId: 'att-3',
  state: 'OUTCOME_UNKNOWN',
};
assert.equal(presentAttempt(outcomeUnknown).status, 'outcome_unknown');
assert.equal(presentAttempt(outcomeUnknown).requiresUserAction, true);

const toolOutcomeUnknown: AiAttemptView = {
  ...failedNoTool,
  attemptId: 'att-4',
  state: 'TOOL_OUTCOME_UNKNOWN',
  toolStarted: true,
  toolOutcomeUnknown: true,
};
assert.equal(presentAttempt(toolOutcomeUnknown).status, 'tool_outcome_unknown');
assert.equal(decideManualRetry(toolOutcomeUnknown).requiresDuplicateToolWarning, true);

const active: AiAttemptView = { ...failedNoTool, attemptId: 'att-5', state: 'ACTIVE' };
assert.equal(decideManualRetry(active).allowed, false);
assert.equal(mapProviderBusyToSendBlock(), 'PROVIDER_BUSY');
assert.equal(providerBusyMessageI18nKey(), 'ai.subscription.attempt.providerBusy');

// --- legacy history confirmation ---
const legacyMessage = presentLegacyMessage(
  { messageId: 'old-1', legacyUnknown: true },
  modelRefKey,
);
assert.equal(legacyMessage.legacyUnknown, true);
assert.equal(legacyMessage.badgeI18nKey, 'ai.subscription.legacy.unknownModel');
assert.equal(legacyMessage.canReadAndCopy, true);
assert.equal(legacyMessage.requiresModelConfirmationBeforeSend, true);
assert.equal(legacyMessage.preselectedModelRefKey, modelRefKey);
assert.equal(legacyMessage.autoConfirmGlobalDefault, false, 'global default never auto-confirms');

assert.equal(
  conversationRequiresLegacyModelConfirm([{ messageId: 'old-1', legacyUnknown: true }]),
  true,
);

const legacyBlocked = resolveLegacySendGate({
  conversationHasLegacyMessages: true,
  userConfirmedModelRefKey: null,
  selectedModelRefKey: modelRefKey,
});
assert.equal(legacyBlocked.blocked, true);
assert.equal(legacyBlocked.blockReason, 'LEGACY_MODEL_UNCONFIRMED');

const legacyConfirmed = resolveLegacySendGate({
  conversationHasLegacyMessages: true,
  userConfirmedModelRefKey: modelRefKey,
  availableModelRefKeys: [modelRefKey],
});
assert.equal(legacyConfirmed.blocked, false);
assert.equal(legacyConfirmed.confirmedModelRefKey, modelRefKey);

const nextMessages = applyConversationModelChoice({
  historicalMessages: [{ messageId: 'old-1', legacyUnknown: true }],
  confirmedModelRefKey: modelRefKey,
  confirmedModelDisplayName: 'GPT-5',
  nextUserMessageId: 'new-1',
});
assert.equal(nextMessages[0].legacyUnknown, true, 'historical identity is not rewritten');
assert.equal(nextMessages[1].modelRefKey, modelRefKey);
assert.equal(nextMessages[1].legacyUnknown, false);

// --- migration defaults + envelope adapter ---
assert.deepEqual(
  resolveMigrationDefaultPlan({
    backendHasValidDefault: true,
    backendDefaultModelRefKey: 'config:1',
    legacySelectedModelMatchesDefaultConfig: true,
    legacyCandidateValid: true,
    sourcesConflict: false,
    referencesBuiltInPreset: false,
    userSkipped: false,
  }),
  {
    decision: 'KEEP_BACKEND_DEFAULT',
    preselectedModelRefKey: 'config:1',
    requireUserConfirmation: false,
  },
);

assert.equal(
  resolveMigrationDefaultPlan({
    backendHasValidDefault: false,
    legacySelectedModelMatchesDefaultConfig: true,
    legacyCandidateModelRefKey: 'config:legacy',
    legacyCandidateValid: true,
    sourcesConflict: false,
    referencesBuiltInPreset: false,
    userSkipped: false,
  }).requireUserConfirmation,
  true,
);

assert.equal(
  resolveMigrationDefaultPlan({
    backendHasValidDefault: false,
    legacySelectedModelMatchesDefaultConfig: false,
    legacyCandidateValid: false,
    sourcesConflict: true,
    referencesBuiltInPreset: false,
    userSkipped: false,
  }).decision,
  'REQUIRE_EXPLICIT_CHOICE',
);

assert.equal(
  resolveMigrationDefaultPlan({
    backendHasValidDefault: false,
    legacySelectedModelMatchesDefaultConfig: false,
    legacyCandidateValid: true,
    sourcesConflict: false,
    referencesBuiltInPreset: false,
    userSkipped: true,
  }).decision,
  'NO_DEFAULT',
);

const importAttempt: AiSecretImportAttemptView = {
  attemptId: 'imp-1',
  completed: false,
  items: [
    { itemId: 'item-1', configName: 'Work GPT', provider: 'OPENAI', status: 'PENDING' },
    { itemId: 'item-2', configName: 'Claude', provider: 'CLAUDE', status: 'PENDING' },
  ],
};
const progress = summarizeMigrationProgress(importAttempt, [
  { itemId: 'item-1', success: true },
  { itemId: 'item-2', success: false, errorCode: 'WRITE_FAILED' },
]);
assert.equal(progress.completed, false, 'any failure prevents overall completion');
assert.deepEqual(progress.deletableLocalItemIds, ['item-1']);
assert.deepEqual(progress.retryableItemIds, ['item-2']);
const uncertainProgress = summarizeMigrationProgress(importAttempt, [
  { itemId: 'item-1', success: false, errorCode: 'IMPORT_OUTCOME_UNKNOWN' },
]);
assert.deepEqual(uncertainProgress.retryableItemIds, [], 'ambiguous writes are never automatically replayed');

const visible = listVisibleMigrationItems(importAttempt.items);
assert.equal(
  JSON.stringify(visible).includes('sk-'),
  false,
  'migration list never includes key-like material',
);

const envelope = buildEncryptedImportEnvelope({
  attemptId: 'imp-1',
  itemId: 'item-1',
  nonceBase64: 'nonce',
  wrappedAesKeyBase64: 'wrap',
  ciphertextBase64: 'cipher',
  expiresAtEpochMs: Date.parse('2026-07-31T12:00:00.000Z'),
});
assert.equal(envelope.schemaVersion, 1);
assert.equal(envelope.attemptId, 'imp-1');
assert.equal(envelope.wrappedKeyBase64, 'wrap');
assert.throws(
  () => resolveConfirmedDefaultItemId({
    backendHasValidDefault: false,
    itemIds: ['item-1'],
    selectedItemId: undefined,
  }),
  /MIGRATION_DEFAULT_CONFIRM_REQUIRED/,
);
assert.equal(resolveConfirmedDefaultItemId({
  backendHasValidDefault: false,
  itemIds: ['item-1'],
  selectedItemId: 'item-1',
}), 'item-1');
assert.equal(resolveConfirmedDefaultItemId({
  backendHasValidDefault: false,
  itemIds: ['item-1'],
  selectedItemId: null,
}), null);
assert.equal(resolveConfirmedDefaultItemId({
  backendHasValidDefault: true,
  itemIds: ['item-1'],
  selectedItemId: 'item-1',
}), null, 'an existing backend default is never replaced by migration');

// --- send gate integration ---
assert.equal(
  evaluateSubscriptionSendGate({
    surfaceAvailable: true,
    selectedModelRefKey: modelRefKey,
    connections: [connected],
    snapshots: [availableSnapshot],
  }).allowed,
  true,
);

assert.equal(
  evaluateSubscriptionSendGate({
    surfaceAvailable: true,
    selectedModelRefKey: modelRefKey,
    connections: [discoveryFailed],
    snapshots: [availableSnapshot],
  }).blockReason,
  'DISCOVERY_FAILED',
);

assert.equal(
  evaluateSubscriptionSendGate({
    surfaceAvailable: true,
    selectedModelRefKey: modelRefKey,
    connections: [connected],
    snapshots: [{ ...availableSnapshot, available: false, disabledReason: 'STALE' }],
  }).blockReason,
  'MODEL_STALE_OR_DISABLED',
);

assert.equal(
  evaluateSubscriptionSendGate({
    surfaceAvailable: true,
    selectedModelRefKey: modelRefKey,
    connections: [connected],
    snapshots: [availableSnapshot],
    providerBusy: true,
  }).createAttempt,
  false,
);

assert.equal(
  evaluateSubscriptionSendGate({
    surfaceAvailable: true,
    selectedModelRefKey: modelRefKey,
    connections: [connected],
    snapshots: [availableSnapshot],
    legacyGate: { blocked: true, blockReason: 'LEGACY_MODEL_UNCONFIRMED', confirmedModelRefKey: null },
  }).blockReason,
  'LEGACY_MODEL_UNCONFIRMED',
);

// API-key style selection bypasses subscription blocks
assert.equal(
  evaluateSubscriptionSendGate({
    surfaceAvailable: true,
    selectedModelRefKey: 'config:local-1',
    connections: [chatGptDisconnected],
    snapshots: [],
  }).allowed,
  true,
  'API-key users are not forced into subscription login',
);

// chatGuards + model select sections
assert.equal(
  evaluateChatSendGuard({
    surfaceAvailable: true,
    selectedModelValue: 'config:local-1',
    modelOption: {
      value: 'config:local-1',
      label: 'Local',
      provider: 'OPENAI',
      model: 'gpt-4o',
      customOption: true,
    },
    connections: [chatGptDisconnected],
    snapshots: [],
    providerBusy: false,
    conversationHasLegacyMessages: false,
  }).allowed,
  true,
  'API-key chat send is not forced through subscription login',
);

assert.equal(
  evaluateChatSendGuard({
    surfaceAvailable: true,
    selectedModelValue: modelRefKey,
    modelOption: {
      value: modelRefKey,
      label: 'GPT-5',
      provider: 'OPENAI',
      model: 'gpt-5',
      subscriptionOption: true,
      selectable: true,
    },
    connections: [connected],
    snapshots: [availableSnapshot],
    providerBusy: true,
    conversationHasLegacyMessages: false,
  }).blockReason,
  'PROVIDER_BUSY',
);

const selectSections = buildModelSelectSections({
  subscriptionEnabled: true,
  connections: [chatGptDisconnected, superGrokWaitlist],
  snapshots: [],
  apiKeyOptions: [{ label: 'Work GPT', value: 'config:1' }],
  formatSnapshotTime: (iso) => iso,
  recentlyConfirmedLabel: 'Recently confirmed available',
  quickConnectLabel: (name) => `Connect ${name}`,
  lastAvailableLabel: 'Last available',
});
assert.ok(selectSections.some((section) => section.key === 'api-key'));
assert.ok(selectSections.some((section) => section.key === 'subscription-connect'));
assert.equal(
  selectSections.some((section) => section.options.some((option) => String(option.label).includes('SuperGrok'))),
  false,
  'SuperGrok does not appear as an operable login option',
);
assert.equal(isSubscriptionConnectOption(subscriptionConnectOptionValue('OPENAI')), true);

const modelSelectUx = modelSelectUxModule as unknown as Record<string, unknown>;
assert.equal(
  typeof modelSelectUx.resolveChatGptConnectEntry,
  'function',
  'the real model selector has a pure ChatGPT onboarding action decision',
);
const resolveChatGptConnectEntry = modelSelectUx.resolveChatGptConnectEntry as (params: {
  communityRuntime: boolean;
  packagedJcefDesktop: boolean;
  hydrated: boolean;
  surfaceAvailable: boolean;
  backendCapability: { enabled: boolean; disabledReason: string } | null;
  lastErrorCode: string | null;
  connections: readonly AiProviderConnectionView[];
}) => { provider: 'OPENAI'; action: 'CONNECT' | 'OPEN_SETTINGS' } | null;
assert.deepEqual(
  resolveChatGptConnectEntry({
    communityRuntime: true,
    packagedJcefDesktop: true,
    hydrated: true,
    surfaceAvailable: true,
    backendCapability: { enabled: true, disabledReason: 'NONE' },
    lastErrorCode: null,
    connections: [chatGptDisconnected],
  }),
  { provider: 'OPENAI', action: 'CONNECT' },
  'an eligible disconnected ChatGPT account starts login directly',
);
assert.deepEqual(
  resolveChatGptConnectEntry({
    communityRuntime: true,
    packagedJcefDesktop: true,
    hydrated: true,
    surfaceAvailable: false,
    backendCapability: { enabled: false, disabledReason: 'APP_SERVER_UNAVAILABLE' },
    lastErrorCode: 'CAPABILITY_FETCH_FAILED',
    connections: [],
  }),
  { provider: 'OPENAI', action: 'OPEN_SETTINGS' },
  'a recoverable bridge failure keeps a visible selector action that opens settings',
);
assert.deepEqual(
  resolveChatGptConnectEntry({
    communityRuntime: true,
    packagedJcefDesktop: true,
    hydrated: true,
    surfaceAvailable: false,
    backendCapability: { enabled: false, disabledReason: 'APP_SERVER_UNAVAILABLE' },
    lastErrorCode: null,
    connections: [],
  }),
  { provider: 'OPENAI', action: 'OPEN_SETTINGS' },
  'a disabled app-server capability keeps a visible selector recovery action',
);
assert.equal(
  resolveChatGptConnectEntry({
    communityRuntime: true,
    packagedJcefDesktop: true,
    hydrated: true,
    surfaceAvailable: true,
    backendCapability: { enabled: true, disabledReason: 'NONE' },
    lastErrorCode: null,
    connections: [connected],
  }),
  null,
  'connected accounts do not show a redundant login action',
);

assert.equal(
  typeof modelSelectUx.findNewSelectableSubscriptionModelKeys,
  'function',
  'post-login guidance compares selectable model snapshots without relying on secrets',
);
const findNewSelectableSubscriptionModelKeys = modelSelectUx.findNewSelectableSubscriptionModelKeys as (
  previous: readonly AiModelSnapshotView[],
  current: readonly AiModelSnapshotView[],
) => string[];
assert.deepEqual(findNewSelectableSubscriptionModelKeys([], [availableSnapshot]), [modelRefKey]);
assert.deepEqual(
  findNewSelectableSubscriptionModelKeys([], [
    { ...availableSnapshot, available: false, disabledReason: 'STALE' },
  ]),
  [],
  'stale snapshots never trigger a models-ready prompt',
);

const communitySettingSource = readFileSync('src/blocks/Setting/CommunitySetting.tsx', 'utf8');
assert.match(
  communitySettingSource,
  /isSubscriptionManagementEntryVisible/,
  'Community settings uses the recoverable management-entry decision instead of hiding on every capability error',
);

const aiModelSelectSource = readFileSync('src/blocks/AI/components/AIModelSelect/index.tsx', 'utf8');
assert.match(
  aiModelSelectSource,
  /resolveChatGptConnectEntry/,
  'the actual AIModelSelect component wires the ChatGPT onboarding action',
);
assert.match(
  aiModelSelectSource,
  /openToken/,
  'the actual selector supports a one-shot post-discovery open signal',
);

const aiChatSource = readFileSync('src/blocks/AI/index.tsx', 'utf8');
assert.match(
  aiChatSource,
  /decideSubscriptionModelRefresh/,
  'AI chat reloads options when subscription models recover, independently of login guidance',
);
assert.match(
  aiChatSource,
  /modelSelectOpenToken/,
  'AI chat forwards a post-login selector-open signal',
);
assert.match(
  aiChatSource,
  /activeConnectAttemptIdByProvider/,
  'post-login guidance is fenced to a connection attempt and does not open on ordinary app startup',
);

async function verifyStartupHydrationOrdering(): Promise<void> {
  const events: string[] = [];
  let releaseSurface!: () => void;
  const surfaceReady = new Promise<void>((resolve) => {
    releaseSurface = resolve;
  });
  const hydration = hydrateSubscriptionModelOptions(
    async () => {
      events.push('surface:start');
      await surfaceReady;
      events.push('surface:ready');
    },
    async () => {
      events.push('models:loaded');
    },
    () => true,
  );
  await Promise.resolve();
  assert.deepEqual(events, ['surface:start'], 'model options wait for subscription surface hydration');
  releaseSurface();
  await hydration;
  assert.deepEqual(events, ['surface:start', 'surface:ready', 'models:loaded']);

  const recoveryEvents: string[] = [];
  let recoveryRefreshes = 0;
  await hydrateSubscriptionModelOptions(
    async () => {
      recoveryRefreshes += 1;
      recoveryEvents.push(`surface:${recoveryRefreshes}`);
    },
    async () => {
      recoveryEvents.push(`models:${recoveryRefreshes}`);
    },
    () => true,
    {
      shouldRetry: () => recoveryRefreshes === 1,
      waitForRetry: async () => {
        recoveryEvents.push('retry:wait');
      },
      maxRecoveryRetries: 2,
    },
  );
  assert.deepEqual(
    recoveryEvents,
    ['surface:1', 'models:1', 'retry:wait', 'surface:2', 'models:2'],
    'connected startup with no selectable catalog refreshes again after backend recovery without login',
  );

  let componentActive = true;
  let releaseCancelledSurface!: () => void;
  let cancelledModelLoads = 0;
  const cancelledSurfaceReady = new Promise<void>((resolve) => {
    releaseCancelledSurface = resolve;
  });
  const cancelledHydration = hydrateSubscriptionModelOptions(
    async () => cancelledSurfaceReady,
    async () => {
      cancelledModelLoads += 1;
    },
    () => componentActive,
  );
  componentActive = false;
  releaseCancelledSurface();
  await cancelledHydration;
  assert.equal(cancelledModelLoads, 0, 'an unmounted AI surface never applies a late model load');
}

verifyStartupHydrationOrdering()
  .then(() => console.log('Subscription AI renderer flow tests passed.'))
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
