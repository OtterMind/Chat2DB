package ai.chat2db.community.tools.model.agent.runtime;

public record AgentRuntimeEnvironmentRequest(
        String applicationVersion,
        String operatingSystem,
        String architecture) {

    public AgentRuntimeEnvironmentRequest {
        requireText(applicationVersion, "applicationVersion");
        requireText(operatingSystem, "operatingSystem");
        requireText(architecture, "architecture");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
