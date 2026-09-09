package ai.chat2db.community.domain.api.service.agent;

import java.util.function.BooleanSupplier;
import ai.chat2db.community.domain.api.model.agent.AgentShellCommand;

public interface AgentShellExecutor {
    AgentShellCommand prepare(String sessionId, String command);
    String execute(AgentShellCommand command, BooleanSupplier cancelled) throws Exception;
}
