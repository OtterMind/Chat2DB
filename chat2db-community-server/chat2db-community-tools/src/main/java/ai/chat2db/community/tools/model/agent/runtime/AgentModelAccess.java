package ai.chat2db.community.tools.model.agent.runtime;

public record AgentModelAccess(
        String provider,
        String modelId,
        String api,
        String baseUrl,
        String ticket) {

    public AgentModelAccess {
        requireText(provider, "provider");
        requireText(modelId, "modelId");
        requireText(api, "api");
        requireText(baseUrl, "baseUrl");
        requireText(ticket, "ticket");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
