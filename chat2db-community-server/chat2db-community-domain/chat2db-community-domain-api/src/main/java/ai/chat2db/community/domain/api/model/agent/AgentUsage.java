package ai.chat2db.community.domain.api.model.agent;

public record AgentUsage(
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long reasoningTokens,
        long totalTokens,
        Long contextWindow) {

    public AgentUsage {
        if (inputTokens < 0 || cachedInputTokens < 0 || outputTokens < 0
                || reasoningTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
        if (contextWindow != null && contextWindow < 1) {
            throw new IllegalArgumentException("contextWindow must be greater than zero");
        }
    }
}
