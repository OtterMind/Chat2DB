package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecision;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import java.util.function.BooleanSupplier;

public interface AgentApprovalService {
    AgentApprovalDecision awaitDecision(AgentApproval approval, Long userId, Runnable publish, BooleanSupplier active);
    void decide(String sessionId, String approvalId, Long userId, AgentApprovalDecision decision);
}
