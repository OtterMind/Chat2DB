import type { AiAttemptState, AiAttemptView, AiSendBlockReason } from '@/typings/aiSubscription';

export type AttemptUserFacingStatus =
  | 'in_progress'
  | 'completed'
  | 'failed'
  | 'interrupted'
  | 'outcome_unknown'
  | 'tool_outcome_unknown'
  | 'partial_visible';

export interface AttemptPresentation {
  status: AttemptUserFacingStatus;
  statusI18nKey: string;
  showPartialOutput: boolean;
  includePartialInFutureContext: boolean;
  canManualRetry: boolean;
  requiresToolRetryWarning: boolean;
  requiresUserAction: boolean;
  isTerminal: boolean;
}

export interface ManualRetryDecision {
  allowed: boolean;
  requiresDuplicateToolWarning: boolean;
  /** Always create a new backend-issued attempt; never reuse attemptId. */
  createsNewAttempt: boolean;
  blockReason?: 'ACTIVE_ATTEMPT' | 'NONE';
}

const IN_PROGRESS: ReadonlySet<AiAttemptState> = new Set([
  'CREATED',
  'SUBMITTING',
  'ACTIVE',
  'TOOL_ACTIVE',
  'OUTPUT_VISIBLE',
]);

export function presentAttempt(attempt: AiAttemptView): AttemptPresentation {
  switch (attempt.state) {
    case 'CREATED':
    case 'SUBMITTING':
    case 'ACTIVE':
    case 'TOOL_ACTIVE':
      return {
        status: 'in_progress',
        statusI18nKey: 'ai.subscription.attempt.inProgress',
        showPartialOutput: !!attempt.partialOutput,
        includePartialInFutureContext: false,
        canManualRetry: false,
        requiresToolRetryWarning: false,
        requiresUserAction: false,
        isTerminal: false,
      };
    case 'OUTPUT_VISIBLE':
      return {
        status: 'partial_visible',
        statusI18nKey: 'ai.subscription.attempt.partialVisible',
        showPartialOutput: true,
        includePartialInFutureContext: false,
        canManualRetry: false,
        requiresToolRetryWarning: false,
        requiresUserAction: false,
        isTerminal: false,
      };
    case 'COMPLETED':
      return {
        status: 'completed',
        statusI18nKey: 'ai.subscription.attempt.completed',
        showPartialOutput: !!attempt.partialOutput,
        includePartialInFutureContext: true,
        canManualRetry: false,
        requiresToolRetryWarning: false,
        requiresUserAction: false,
        isTerminal: true,
      };
    case 'FAILED':
      return {
        status: 'failed',
        statusI18nKey: 'ai.subscription.attempt.failed',
        showPartialOutput: !!attempt.partialOutput,
        includePartialInFutureContext: false,
        canManualRetry: true,
        requiresToolRetryWarning: attempt.toolStarted,
        requiresUserAction: true,
        isTerminal: true,
      };
    case 'INTERRUPTED':
      return {
        status: 'interrupted',
        statusI18nKey: 'ai.subscription.attempt.interrupted',
        showPartialOutput: !!attempt.partialOutput,
        includePartialInFutureContext: false,
        canManualRetry: true,
        requiresToolRetryWarning: attempt.toolStarted,
        requiresUserAction: true,
        isTerminal: true,
      };
    case 'OUTCOME_UNKNOWN':
      return {
        status: 'outcome_unknown',
        statusI18nKey: 'ai.subscription.attempt.outcomeUnknown',
        showPartialOutput: !!attempt.partialOutput,
        includePartialInFutureContext: false,
        canManualRetry: true,
        requiresToolRetryWarning: attempt.toolStarted,
        requiresUserAction: true,
        isTerminal: true,
      };
    case 'TOOL_OUTCOME_UNKNOWN':
      return {
        status: 'tool_outcome_unknown',
        statusI18nKey: 'ai.subscription.attempt.toolOutcomeUnknown',
        showPartialOutput: true,
        includePartialInFutureContext: false,
        canManualRetry: true,
        requiresToolRetryWarning: true,
        requiresUserAction: true,
        isTerminal: true,
      };
    default:
      return {
        status: 'failed',
        statusI18nKey: 'ai.subscription.attempt.failed',
        showPartialOutput: false,
        includePartialInFutureContext: false,
        canManualRetry: false,
        requiresToolRetryWarning: false,
        requiresUserAction: true,
        isTerminal: true,
      };
  }
}

/**
 * Manual retry always creates a new attempt.
 * Tool-started attempts require an explicit duplicate-database-operation warning.
 * No automatic fallback or auto-retry after tools.
 */
export function decideManualRetry(attempt: AiAttemptView): ManualRetryDecision {
  const presentation = presentAttempt(attempt);
  if (IN_PROGRESS.has(attempt.state)) {
    return {
      allowed: false,
      requiresDuplicateToolWarning: false,
      createsNewAttempt: true,
      blockReason: 'ACTIVE_ATTEMPT',
    };
  }
  if (!presentation.canManualRetry) {
    return {
      allowed: false,
      requiresDuplicateToolWarning: false,
      createsNewAttempt: true,
      blockReason: 'NONE',
    };
  }
  return {
    allowed: true,
    requiresDuplicateToolWarning: presentation.requiresToolRetryWarning || attempt.toolStarted,
    createsNewAttempt: true,
  };
}

export function mapProviderBusyToSendBlock(): AiSendBlockReason {
  return 'PROVIDER_BUSY';
}

export function providerBusyMessageI18nKey(): string {
  return 'ai.subscription.attempt.providerBusy';
}

export function toolRetryWarningI18nKey(): string {
  return 'ai.subscription.attempt.toolRetryWarning';
}

export function toolOutcomeUnknownI18nKey(): string {
  return 'ai.subscription.attempt.toolOutcomeUnknown';
}
