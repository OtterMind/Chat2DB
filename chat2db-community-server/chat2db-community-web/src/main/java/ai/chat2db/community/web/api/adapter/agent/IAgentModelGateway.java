package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.tools.agent.runtime.IAgentModelAccessProvider;
import ai.chat2db.community.web.api.model.response.agent.AgentModelGatewayResponse;
import java.io.IOException;
import java.util.Map;

public interface IAgentModelGateway extends IAgentModelAccessProvider {
    AgentModelGatewayResponse forward(String ticket, String remoteAddress, String path,
            Map<String, String> headers, byte[] body) throws IOException;
}
