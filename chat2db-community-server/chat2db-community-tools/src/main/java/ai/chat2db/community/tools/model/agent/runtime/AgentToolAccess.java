package ai.chat2db.community.tools.model.agent.runtime;

import java.util.List;
import java.util.Map;

public record AgentToolAccess(String baseUrl, String ticket, List<Tool> tools, String userSkillDirectory) {
    public AgentToolAccess(String baseUrl, String ticket, List<Tool> tools) {
        this(baseUrl, ticket, tools, null);
    }

    /**
     * A tool the runtime may call. {@code group} names the family the tool belongs to and
     * {@code defaultActive} tells the extension whether the tool belongs to the first request of a
     * session; tools that are registered but inactive only cost prompt space once a model asks for them.
     */
    public record Tool(String name, String description, Map<String, Object> parameters,
                       String promptSnippet, List<String> promptGuidelines, String group,
                       boolean defaultActive) {
        public Tool(String name, String description, Map<String, Object> parameters,
                    String promptSnippet, List<String> promptGuidelines) {
            this(name, description, parameters, promptSnippet, promptGuidelines, null, true);
        }
    }
}
