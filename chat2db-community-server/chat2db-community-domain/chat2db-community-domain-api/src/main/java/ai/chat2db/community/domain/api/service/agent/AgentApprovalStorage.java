package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatus;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import java.util.List;

public interface AgentApprovalStorage {

    AgentApproval create(AgentApproval approval, Long userId);

    AgentApproval get(String sessionId, String approvalId, Long userId);

    List<AgentApproval> list(String sessionId, Long userId);

    boolean compareAndSet(AgentApproval approval, AgentApprovalStatus expectedStatus, Long userId);
}
