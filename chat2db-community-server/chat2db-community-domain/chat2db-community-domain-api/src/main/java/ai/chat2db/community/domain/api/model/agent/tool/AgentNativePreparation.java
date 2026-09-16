package ai.chat2db.community.domain.api.model.agent.tool;

/** An opaque authorization for one native invocation and its output finalization. */
public record AgentNativePreparation(String workingDirectory, String preparationId, String allowedRoot) {
    public AgentNativePreparation(String workingDirectory, String preparationId) {
        this(workingDirectory, preparationId, workingDirectory);
    }
}
