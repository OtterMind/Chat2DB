package ai.chat2db.community.domain.api.model.agent.tool;

import ai.chat2db.community.domain.api.enums.agent.AgentToolCategory;
import ai.chat2db.community.domain.api.enums.agent.AgentToolStatus;

public record AgentToolState(String name, String description, AgentToolCategory category, AgentToolStatus status) {
}
