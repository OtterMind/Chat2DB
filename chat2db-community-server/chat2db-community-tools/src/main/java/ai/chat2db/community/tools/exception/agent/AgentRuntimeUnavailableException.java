package ai.chat2db.community.tools.exception.agent;

public class AgentRuntimeUnavailableException extends RuntimeException {

    public AgentRuntimeUnavailableException(String runtimeId) {
        super("Agent runtime is not available: " + runtimeId);
    }
}
