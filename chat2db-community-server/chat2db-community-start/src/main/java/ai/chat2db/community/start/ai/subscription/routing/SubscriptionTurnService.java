package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutput;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutputKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerDisabledReason;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerException;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerPort;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerThreadView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerTurnView;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChatGPT subscription turn lifecycle over CodexAppServerPort + durable attempt journal.
 * Never auto-replays ambiguous turn/start; default remains fail-closed when the port is disabled.
 */
public final class SubscriptionTurnService {

    private final CodexAppServerPort appServer;
    private final IAiSubscriptionStateRepository repository;
    private final boolean featureEnabled;

    public SubscriptionTurnService(
            CodexAppServerPort appServer,
            IAiSubscriptionStateRepository repository,
            boolean featureEnabled) {
        this.appServer = Objects.requireNonNull(appServer, "appServer");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.featureEnabled = featureEnabled;
    }

    public SubscriptionTurnResult execute(ChatRequest request, AiModelRef modelRef) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(modelRef, "modelRef");

        String messageId = blankToNewId(request.getMessageId());
        if (!featureEnabled || !appServer.isEnabled()) {
            return SubscriptionTurnResult.rejected(messageId, "SUBSCRIPTION_ROUTE_DISABLED");
        }

        String attemptId = UUID.randomUUID().toString();
        AiProviderEnum provider = modelRef.provider();
        AiProviderConnection connection = repository.connection(provider);
        long fence = connection.fenceGeneration();
        if (!repository.tryCreateAttemptAndAcquireProviderLease(
                attemptId, messageId, provider, AiAttemptState.CREATED, fence)) {
            return SubscriptionTurnResult.busy(messageId);
        }

        repository.saveMessageModelSnapshot(messageId, modelRef);

        repository.transitionAttempt(attemptId, AiAttemptState.CREATED, AiAttemptState.SUBMITTING,
                null, null);

        String threadId = null;
        String turnId = null;
        List<AiAttemptOutput> localOutputs = new ArrayList<>();
        AtomicLong sequence = new AtomicLong(0);

        try {
            AppServerThreadView thread = appServer.startThread(modelRef.modelId());
            threadId = thread.threadId();

            AppServerTurnView turn;
            try {
                turn = appServer.startTurn(threadId, request.getInput());
            } catch (AppServerException ex) {
                return handleAmbiguousTurnStart(attemptId, messageId, threadId, ex);
            } catch (RuntimeException ex) {
                return handleAmbiguousTurnStart(attemptId, messageId, threadId,
                        new AppServerException(
                                AppServerDisabledReason.PROCESS_CRASHED,
                                "turn/start failed ambiguously",
                                ex));
            }

            turnId = turn.turnId();
            repository.transitionAttempt(attemptId, AiAttemptState.SUBMITTING, AiAttemptState.ACTIVE,
                    threadId, turnId);

            // Partial/assistant output is visible but excluded from later model context by default.
            long seq = sequence.incrementAndGet();
            String content = "";
            repository.appendAttemptOutput(attemptId, seq, AiAttemptOutputKind.ASSISTANT_TEXT,
                    content, true, false);
            localOutputs.addAll(repository.listAttemptOutputs(attemptId));

            // Without a live event stream in this unit, mark terminal COMPLETED when turn accepted.
            // Event-driven ACTIVE→OUTPUT_VISIBLE→COMPLETED is handled when notifications are wired.
            String status = turn.status() == null ? "" : turn.status();
            if ("interrupted".equalsIgnoreCase(status)) {
                repository.transitionAttempt(attemptId, AiAttemptState.ACTIVE, AiAttemptState.INTERRUPTED,
                        threadId, turnId);
                return result(attemptId, messageId, AiAttemptState.INTERRUPTED, threadId, turnId,
                        repository.listAttemptOutputs(attemptId), "INTERRUPTED");
            }

            repository.transitionAttempt(attemptId, AiAttemptState.ACTIVE, AiAttemptState.OUTPUT_VISIBLE,
                    threadId, turnId);
            repository.transitionAttempt(attemptId, AiAttemptState.OUTPUT_VISIBLE, AiAttemptState.COMPLETED,
                    threadId, turnId);

            return result(attemptId, messageId, AiAttemptState.COMPLETED, threadId, turnId,
                    repository.listAttemptOutputs(attemptId), null);
        } catch (AppServerException ex) {
            safeFail(attemptId, threadId, turnId);
            return result(attemptId, messageId, AiAttemptState.FAILED, threadId, turnId,
                    repository.listAttemptOutputs(attemptId), "APP_SERVER_ERROR");
        } catch (RuntimeException ex) {
            safeFail(attemptId, threadId, turnId);
            return result(attemptId, messageId, AiAttemptState.FAILED, threadId, turnId,
                    repository.listAttemptOutputs(attemptId), "SUBSCRIPTION_TURN_FAILED");
        } finally {
            releaseLeaseWhenTerminal(provider, attemptId);
        }
    }

    /**
     * Best-effort interrupt of an active attempt. Never replays turn/start.
     */
    public SubscriptionTurnResult interrupt(String attemptId) {
        Optional<AiAttempt> attemptOpt = repository.findAttempt(attemptId);
        if (attemptOpt.isEmpty()) {
            return SubscriptionTurnResult.rejected(null, "ATTEMPT_NOT_FOUND");
        }
        AiAttempt attempt = attemptOpt.get();
        if (attempt.externalThreadId() != null && attempt.externalTurnId() != null
                && appServer.isEnabled()) {
            try {
                appServer.interruptTurn(attempt.externalThreadId(), attempt.externalTurnId());
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        if (attempt.state().canTransitionTo(AiAttemptState.INTERRUPTED)) {
            repository.transitionAttempt(attemptId, attempt.state(), AiAttemptState.INTERRUPTED,
                    attempt.externalThreadId(), attempt.externalTurnId());
        }
        return result(attemptId, attempt.messageId(), AiAttemptState.INTERRUPTED,
                attempt.externalThreadId(), attempt.externalTurnId(),
                repository.listAttemptOutputs(attemptId), "INTERRUPTED");
    }

    private SubscriptionTurnResult handleAmbiguousTurnStart(
            String attemptId,
            String messageId,
            String threadId,
            AppServerException ex) {
        // Never auto-replay turn/start. Prove absence via thread/read or mark OUTCOME_UNKNOWN.
        boolean provedAbsent = false;
        if (threadId != null && appServer.isEnabled()) {
            try {
                AppServerThreadView read = appServer.readThread(threadId, true);
                // Without a structured turn list in the port DTO, we cannot prove absence safely.
                provedAbsent = read == null || read.threadId() == null;
            } catch (RuntimeException ignored) {
                provedAbsent = false;
            }
        }
        if (provedAbsent) {
            repository.transitionAttempt(attemptId, AiAttemptState.SUBMITTING, AiAttemptState.FAILED,
                    threadId, null);
            return result(attemptId, messageId, AiAttemptState.FAILED, threadId, null,
                    repository.listAttemptOutputs(attemptId), "TURN_START_FAILED");
        }
        repository.transitionAttempt(attemptId, AiAttemptState.SUBMITTING, AiAttemptState.OUTCOME_UNKNOWN,
                threadId, null);
        return result(attemptId, messageId, AiAttemptState.OUTCOME_UNKNOWN, threadId, null,
                repository.listAttemptOutputs(attemptId), "TURN_OUTCOME_UNKNOWN");
    }

    private void safeFail(String attemptId, String threadId, String turnId) {
        repository.findAttempt(attemptId).ifPresent(attempt -> {
            if (attempt.state().canTransitionTo(AiAttemptState.FAILED)) {
                repository.transitionAttempt(attemptId, attempt.state(), AiAttemptState.FAILED,
                        threadId, turnId);
            }
        });
    }

    private void releaseLeaseWhenTerminal(AiProviderEnum provider, String attemptId) {
        repository.findAttempt(attemptId).ifPresent(attempt -> {
            if (attempt.state() == AiAttemptState.COMPLETED
                    || attempt.state() == AiAttemptState.FAILED
                    || attempt.state() == AiAttemptState.INTERRUPTED
                    || attempt.state() == AiAttemptState.OUTCOME_UNKNOWN
                    || attempt.state() == AiAttemptState.TOOL_OUTCOME_UNKNOWN) {
                repository.releaseProviderLease(provider, attemptId);
            }
        });
    }

    private static SubscriptionTurnResult result(
            String attemptId,
            String messageId,
            AiAttemptState state,
            String threadId,
            String turnId,
            List<AiAttemptOutput> outputs,
            String errorCode) {
        return new SubscriptionTurnResult(
                attemptId,
                messageId,
                state,
                threadId,
                turnId,
                List.copyOf(outputs),
                errorCode,
                false);
    }

    private static String blankToNewId(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return value;
    }
}
