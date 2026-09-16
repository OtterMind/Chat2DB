package ai.chat2db.community.tools.model.agent.runtime;

import java.util.List;
import java.util.Map;

public record AgentToolAccess(String baseUrl, String ticket, List<Tool> tools, String userSkillDirectory) {
    public AgentToolAccess(String baseUrl, String ticket, List<Tool> tools) {
        this(baseUrl, ticket, tools, null);
    }
    public record Tool(String name, String description, Map<String, Object> parameters,
                       String promptSnippet, List<String> promptGuidelines) { }
}
