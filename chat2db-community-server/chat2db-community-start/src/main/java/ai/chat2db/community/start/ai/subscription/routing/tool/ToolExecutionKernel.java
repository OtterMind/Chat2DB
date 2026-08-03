package ai.chat2db.community.start.ai.subscription.routing.tool;

import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolExecution;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartDecision;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartResult;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.start.ai.subscription.appserver.Chat2dbMcpToolPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Journals tool intent before execution and never replays STARTED / OUTCOME_UNKNOWN calls.
 * Automatic SELECT/DML/DDL is preserved when tools are enabled; exactly-once is never claimed.
 */
public final class ToolExecutionKernel {

    public static final Set<String> DEFAULT_ALLOWLIST = Chat2dbMcpToolPolicy.DATABASE_TOOLS;

    private final IAiSubscriptionStateRepository repository;
    private final Chat2dbToolExecutor toolExecutor;
    private final Set<String> allowlist;

    public ToolExecutionKernel(
            IAiSubscriptionStateRepository repository,
            Chat2dbToolExecutor toolExecutor) {
        this(repository, toolExecutor, DEFAULT_ALLOWLIST);
    }

    public ToolExecutionKernel(
            IAiSubscriptionStateRepository repository,
            Chat2dbToolExecutor toolExecutor,
            Set<String> allowlist) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.allowlist = Set.copyOf(Objects.requireNonNull(allowlist, "allowlist"));
    }

    public ToolInvocationResult invoke(
            String attemptId,
            long sequence,
            String toolName,
            String argumentsJson) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (toolName == null || !allowlist.contains(toolName)) {
            return ToolInvocationResult.blocked("TOOL_NOT_ALLOWLISTED");
        }
        String args = argumentsJson == null ? "" : argumentsJson;
        String argumentsHash = sha256Hex(args);
        String effectFingerprint = sha256Hex(toolName + "\0" + args);

        AiToolStartResult start = repository.beginToolExecution(
                attemptId, sequence, toolName, argumentsHash, effectFingerprint);

        if (start.decision() == AiToolStartDecision.RETURN_RECORDED_RESULT) {
            String reference = start.execution().safeResultReference();
            return ToolInvocationResult.replayed(reference,
                    "RESULT_ALREADY_RECORDED:" + (reference == null ? "unknown" : reference),
                    start.execution());
        }
        if (start.decision() == AiToolStartDecision.BLOCKED_UNCERTAIN) {
            // STARTED or OUTCOME_UNKNOWN must never re-execute.
            if (start.execution() != null) {
                markAttemptToolUnknown(attemptId);
            }
            return ToolInvocationResult.blockedUncertain(start.execution());
        }

        // STARTED: transition attempt into TOOL_ACTIVE when possible, then execute once.
        tryTransition(attemptId, AiAttemptState.ACTIVE, AiAttemptState.TOOL_ACTIVE);
        tryTransition(attemptId, AiAttemptState.OUTPUT_VISIBLE, AiAttemptState.TOOL_ACTIVE);

        try {
            String toolOutput = toolExecutor.execute(toolName, args);
            String safeRef = "sha256:" + sha256Hex(toolOutput == null ? "" : toolOutput);
            repository.completeToolExecution(attemptId, sequence, safeRef);
            if (!repository.isAttemptLeaseActive(attemptId)) {
                markAttemptToolUnknown(attemptId);
                return ToolInvocationResult.unknown(start.execution(), "TOOL_OUTCOME_UNKNOWN");
            }
            tryTransition(attemptId, AiAttemptState.TOOL_ACTIVE, AiAttemptState.ACTIVE);
            return ToolInvocationResult.executed(safeRef, toolOutput, start.execution());
        } catch (Exception ex) {
            // Crash window after possible target-DB effect: never claim exactly-once.
            // Log class only (no args/SQL/secrets) so operators can see why the attempt was fenced.
            System.getLogger(ToolExecutionKernel.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "subscription tool execution failed tool={0} errorType={1}",
                    toolName,
                    ex.getClass().getSimpleName());
            boolean persisted = markAttemptToolUnknown(attemptId);
            return ToolInvocationResult.unknown(start.execution(), persisted
                    ? "TOOL_OUTCOME_UNKNOWN" : "TOOL_OUTCOME_UNKNOWN_LEDGER_UNAVAILABLE");
        }
    }

    private boolean markAttemptToolUnknown(String attemptId) {
        try {
            return repository.markToolOutcomeUnknownAndReleaseLease(attemptId);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void tryTransition(String attemptId, AiAttemptState expected, AiAttemptState target) {
        try {
            repository.transitionAttempt(attemptId, expected, target, null, null);
        } catch (RuntimeException ignored) {
            // best-effort; repository enforces legality
        }
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record ToolInvocationResult(
            Outcome outcome,
            String safeResultReference,
            String responseText,
            AiToolExecution execution,
            String errorCode) {

        public enum Outcome {
            EXECUTED,
            REPLAYED,
            BLOCKED_UNCERTAIN,
            BLOCKED,
            UNKNOWN
        }

        static ToolInvocationResult executed(String ref, String responseText, AiToolExecution execution) {
            return new ToolInvocationResult(Outcome.EXECUTED, ref, responseText, execution, null);
        }

        static ToolInvocationResult replayed(String ref, String responseText, AiToolExecution execution) {
            return new ToolInvocationResult(Outcome.REPLAYED, ref, responseText, execution, null);
        }

        static ToolInvocationResult blockedUncertain(AiToolExecution execution) {
            return new ToolInvocationResult(Outcome.BLOCKED_UNCERTAIN, null, null, execution, "BLOCKED_UNCERTAIN");
        }

        static ToolInvocationResult blocked(String code) {
            return new ToolInvocationResult(Outcome.BLOCKED, null, null, null, code);
        }

        static ToolInvocationResult unknown(AiToolExecution execution, String code) {
            return new ToolInvocationResult(Outcome.UNKNOWN, null, null, execution, code);
        }
    }
}
