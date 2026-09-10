package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.domain.api.model.agent.AgentToolState;
import java.util.List;

public interface AgentToolAccessService {
    AgentToolAccess issue(String sessionId, AgentRuntimeEventSink eventSink);
    void revoke(String ticket);
    List<AgentToolState> listTools();
    List<String> activeTools(String ticket, String address);
    ai.chat2db.community.domain.api.model.agent.runtime.IAgentToolResult<?> execute(
            String ticket, String address, String toolCallId, String toolName, java.util.Map<String, Object> arguments) throws Exception;
    ai.chat2db.community.domain.api.model.agent.AgentWorkspaceSettings prepareNative(
            String ticket, String address, String toolCallId, String toolName, java.util.Map<String, Object> arguments) throws Exception;
}
