package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecision;
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
    private final Map<String, CompletableFuture<AgentApprovalDecision>> pending = new ConcurrentHashMap<>();

    public AgentApprovalServiceImpl(AgentApprovalStorage storage) {
        this.storage = storage;
    }

    @Override
    public AgentApprovalDecision awaitDecision(AgentApproval approval, Long userId, Runnable publish,
            BooleanSupplier active) {
        CompletableFuture<AgentApprovalDecision> decision = new CompletableFuture<>();
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
                    AgentApprovalDecision answer = decision.get(200, TimeUnit.MILLISECONDS);
                    return active.getAsBoolean() ? answer : AgentApprovalDecision.DENY;
                } catch (TimeoutException ignored) {
                    // Recheck cancellation and expiry while waiting for the user's decision.
                }
            }
            return AgentApprovalDecision.DENY;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return AgentApprovalDecision.DENY;
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
    public void decide(String sessionId, String approvalId, Long userId, AgentApprovalDecision answer) {
        AgentApprovalDecision decision = answer == null ? AgentApprovalDecision.DENY : answer;
        AgentApproval approval = storage.get(sessionId, approvalId, userId);
        if (approval == null) throw new IllegalArgumentException("Approval does not exist");
        CompletableFuture<AgentApprovalDecision> pendingDecision = pending.get(approvalId);
        if (pendingDecision == null || !LocalDateTime.now().isBefore(approval.expiresAt())) {
            throw new IllegalStateException("Approval has expired or its run has stopped");
        }
        AgentApprovalStatus status = decision.allowed() ? AgentApprovalStatus.APPROVED : AgentApprovalStatus.DENIED;
        if (!storage.compareAndSet(withStatus(approval, status), AgentApprovalStatus.PENDING, userId)) {
            throw new IllegalStateException("Approval has already been answered");
        }
        pendingDecision.complete(decision);
        AgentTrace.record("approval.decided", sessionId, approval.runId(),
                Map.of("approvalId", approvalId, "status", status));
    }

    private AgentApproval withStatus(AgentApproval approval, AgentApprovalStatus status) {
        return new AgentApproval(approval.id(), approval.sessionId(), approval.runId(), approval.toolCallId(),
                status, approval.scope(), approval.subjectSha256(), approval.expiresAt());
    }
}
