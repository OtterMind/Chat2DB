package ai.chat2db.community.tools.exception.agent;

public class AgentRuntimeUnavailableException extends RuntimeException {

    public AgentRuntimeUnavailableException(String runtimeId) {
        this(runtimeId, "runtime is not registered");
    }

    public AgentRuntimeUnavailableException(String runtimeId, String reason) {
        super("Agent runtime " + runtimeId + " is unavailable: " + reason);
    }
}
