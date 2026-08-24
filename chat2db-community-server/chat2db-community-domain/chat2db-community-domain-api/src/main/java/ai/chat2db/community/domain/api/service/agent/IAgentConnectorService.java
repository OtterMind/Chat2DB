package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairing;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairingTicket;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorContext;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorSession;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorConversation;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorAuditContext;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorTokenGrant;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.request.agent.AgentConnectorPairingCreateRequest;

import java.util.List;
import java.util.Date;

public interface IAgentConnectorService {
    AgentConnectorPairingTicket createPairing(AgentConnectorPairingCreateRequest request);
    AgentConnectorPairing pairingStatus(String pairingId, String pollToken);
    List<AgentConnectorPairing> listPendingPairings();
    AgentConnectorPairing decidePairing(String pairingId, String agentId, boolean approved,
                                         long expectedRevision, Long ownerId);
    AgentConnectorTokenGrant exchange(String pairingId, String pollToken, String exchangeCode);
    AgentConnectorTokenGrant refresh(String sessionId, String refreshToken);
    AgentConnectorSession revoke(String sessionId, Long ownerId);
    void deleteSession(String sessionId, Long ownerId);
    List<AgentConnectorSession> listSessions(Long ownerId);
    List<AgentConnectorConversation> listConversations(String sessionId, Long ownerId);
    boolean isConnectorTask(String taskId);
    AgentConnectorAuditContext auditContext(String taskId);
    int reconcileSessions(Date idleBefore, int limit);
    AgentRuntimeTaskScope authorizeAccess(String sessionId, String accessToken);
    AgentRuntimeTaskScope authorizeToolCall(String sessionId, String accessToken, String externalSessionId,
                                            String externalCallId, String toolName, String arguments);
    AgentRuntimeTaskScope authorizeInvocation(String sessionId, String accessToken, String externalSessionId,
                                              String externalCallId);
    void completeToolCall(AgentRuntimeTaskScope scope, boolean success, boolean waitingApproval, String result);
    AgentConnectorContext context(String sessionId, String accessToken);
}
