package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentApprovalStatus;

import java.util.List;

public interface AgentApprovalStorage {

    AgentApproval create(AgentApproval approval, Long userId);

    AgentApproval get(String sessionId, String approvalId, Long userId);

    List<AgentApproval> list(String sessionId, Long userId);

    boolean compareAndSet(AgentApproval approval, AgentApprovalStatus expectedStatus, Long userId);
}
