package ai.chat2db.community.domain.api.model.agent.runtime;

public record AgentRuntimeRunRef(String runId, String externalRunId) {

    public AgentRuntimeRunRef {
        requireText(runId, "runId");
        requireText(externalRunId, "externalRunId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
