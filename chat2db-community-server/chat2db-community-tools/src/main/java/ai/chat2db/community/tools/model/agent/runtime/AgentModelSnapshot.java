package ai.chat2db.community.tools.model.agent.runtime;

public record AgentModelSnapshot(
        String modelConfigId,
        long modelRevision,
        String provider,
        String modelId,
        Integer contextWindow,
        Integer maxOutputTokens) {

    public AgentModelSnapshot {
        requireText(modelConfigId, "modelConfigId");
        requireText(provider, "provider");
        requireText(modelId, "modelId");
        if (modelRevision < 1) {
            throw new IllegalArgumentException("modelRevision must be greater than zero");
        }
        requirePositive(contextWindow, "contextWindow");
        requirePositive(maxOutputTokens, "maxOutputTokens");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Integer value, String name) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }
}
