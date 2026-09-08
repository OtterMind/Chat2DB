package ai.chat2db.community.domain.api.model.agent.runtime;

public record AgentRuntimeCancelRequest(String sessionId, String runId, String externalRunId) {

    public AgentRuntimeCancelRequest {
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
        requireText(externalRunId, "externalRunId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
