package ai.chat2db.community.domain.api.model.agent.output;

public record AgentOutputRead(String content, String nextCursor, boolean hasMore,
        long startLine, long endLine, boolean partialLine, Boolean complete, String warning) {
    public AgentOutputRead(String content, String nextCursor, boolean hasMore,
            long startLine, long endLine, boolean partialLine) {
        this(content, nextCursor, hasMore, startLine, endLine, partialLine, null, null);
    }
}
