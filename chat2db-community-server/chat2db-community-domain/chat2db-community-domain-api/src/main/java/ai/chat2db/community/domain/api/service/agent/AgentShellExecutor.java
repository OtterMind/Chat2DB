package ai.chat2db.community.domain.api.service.agent;

import java.util.function.BooleanSupplier;

public interface AgentShellExecutor {
    String execute(String sessionId, String command, BooleanSupplier cancelled) throws Exception;
}
