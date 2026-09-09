package ai.chat2db.community.domain.api.model.agent;

import java.util.List;

public record AgentDirectoryListing(String path, String parent, List<Entry> directories) {
    public record Entry(String name, String path) { }
}
