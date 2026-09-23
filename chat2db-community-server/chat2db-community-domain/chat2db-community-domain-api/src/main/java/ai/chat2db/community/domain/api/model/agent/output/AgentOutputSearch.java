package ai.chat2db.community.domain.api.model.agent.output;

import java.util.List;

public record AgentOutputSearch(List<Match> matches, String nextCursor, boolean hasMore, String warning) {
    public record Match(long line, String content, long byteOffset) { }
}
