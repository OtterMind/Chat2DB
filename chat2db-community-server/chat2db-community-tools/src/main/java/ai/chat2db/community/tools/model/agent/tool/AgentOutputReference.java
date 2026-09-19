package ai.chat2db.community.tools.model.agent.tool;

/** A V2 tool output owned by a conversation; complete refers to this invocation only. */
public record AgentOutputReference(String mode, String artifactId, String path, String format,
        long sizeBytes, boolean complete, boolean previewTruncated, String warning) {
    public static AgentOutputReference unavailable(String warning) {
        return new AgentOutputReference("unavailable", null, null, null, 0, false, true, warning);
    }
}
