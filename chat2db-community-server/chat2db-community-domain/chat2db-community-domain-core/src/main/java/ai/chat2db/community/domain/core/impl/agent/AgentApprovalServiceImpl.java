package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatus;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.service.agent.AgentApprovalService;
import ai.chat2db.community.domain.api.service.agent.AgentApprovalStorage;
import ai.chat2db.community.tools.util.AgentTrace;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

@Service
public class AgentApprovalServiceImpl implements AgentApprovalService {
    private final AgentApprovalStorage storage;
    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    public AgentApprovalServiceImpl(AgentApprovalStorage storage) {
        this.storage = storage;
    }

    @Override
    public boolean awaitDecision(AgentApproval approval, Long userId, Runnable publish, BooleanSupplier active) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        if (pending.putIfAbsent(approval.id(), decision) != null) {
            throw new IllegalStateException("Approval is already pending");
        }
        try {
            storage.create(approval, userId);
            AgentTrace.record("approval.requested", approval.sessionId(), approval.runId(),
                    Map.of("approvalId", approval.id(), "toolCallId", approval.toolCallId(),
                            "subjectSha256", approval.subjectSha256(), "expiresAt", approval.expiresAt()));
            publish.run();
            while (active.getAsBoolean() && LocalDateTime.now().isBefore(approval.expiresAt())) {
                try {
                    return decision.get(200, TimeUnit.MILLISECONDS) && active.getAsBoolean();
                } catch (TimeoutException ignored) {
                    // Recheck cancellation and expiry while waiting for the user's decision.
                }
            }
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IllegalStateException("Approval could not be completed", error.getCause());
        } finally {
            pending.remove(approval.id(), decision);
            AgentApprovalStatus status = LocalDateTime.now().isBefore(approval.expiresAt())
                    ? AgentApprovalStatus.CANCELLED : AgentApprovalStatus.EXPIRED;
            if (storage.compareAndSet(withStatus(approval, status), AgentApprovalStatus.PENDING, userId)) {
                AgentTrace.record("approval.closed", approval.sessionId(), approval.runId(),
                        Map.of("approvalId", approval.id(), "status", status));
            }
        }
    }

    @Override
    public void decide(String sessionId, String approvalId, Long userId, boolean approved) {
        AgentApproval approval = storage.get(sessionId, approvalId, userId);
        if (approval == null) throw new IllegalArgumentException("Approval does not exist");
        CompletableFuture<Boolean> decision = pending.get(approvalId);
        if (decision == null || !LocalDateTime.now().isBefore(approval.expiresAt())) {
            throw new IllegalStateException("Approval has expired or its run has stopped");
        }
        AgentApprovalStatus status = approved ? AgentApprovalStatus.APPROVED : AgentApprovalStatus.DENIED;
        if (!storage.compareAndSet(withStatus(approval, status), AgentApprovalStatus.PENDING, userId)) {
            throw new IllegalStateException("Approval has already been answered");
        }
        decision.complete(approved);
        AgentTrace.record("approval.decided", sessionId, approval.runId(),
                Map.of("approvalId", approvalId, "status", status));
    }

    private AgentApproval withStatus(AgentApproval approval, AgentApprovalStatus status) {
        return new AgentApproval(approval.id(), approval.sessionId(), approval.runId(), approval.toolCallId(),
                status, approval.scope(), approval.subjectSha256(), approval.expiresAt());
    }
}
