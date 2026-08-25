package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairing;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorSession;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorConversation;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorInvocation;

import java.util.List;
import java.util.Date;

public interface IAgentConnectorStorage {
    AgentConnectorPairing createPairing(AgentConnectorPairing pairing);
    AgentConnectorPairing getPairing(String id);
    List<AgentConnectorPairing> listPendingPairings();
    AgentConnectorPairing updatePairing(AgentConnectorPairing pairing, long expectedRevision);
    AgentConnectorSession createSession(AgentConnectorSession session);
    AgentConnectorSession getSession(String id);
    List<AgentConnectorSession> listSessions(Long ownerId);
    List<AgentConnectorSession> listAllSessions();
    List<AgentConnectorSession> listActiveSessionsBefore(Date lastUsedBefore, int limit);
    AgentConnectorSession getSessionByTaskId(String taskId);
    AgentConnectorSession updateSession(AgentConnectorSession session, long expectedRevision);
    void deleteSession(String sessionId);
    AgentConnectorConversation createConversation(AgentConnectorConversation conversation);
    AgentConnectorConversation getConversation(String connectorSessionId, String externalSessionId);
    AgentConnectorConversation getConversationByTaskId(String taskId);
    List<AgentConnectorConversation> listConversations(String connectorSessionId);
    AgentConnectorConversation updateConversation(AgentConnectorConversation conversation, long expectedRevision);
    AgentConnectorInvocation createInvocation(AgentConnectorInvocation invocation);
    AgentConnectorInvocation getInvocation(String conversationId, String externalCallId);
    List<AgentConnectorInvocation> listInvocations(String conversationId);
    AgentConnectorInvocation updateInvocation(AgentConnectorInvocation invocation, long expectedRevision);
}
