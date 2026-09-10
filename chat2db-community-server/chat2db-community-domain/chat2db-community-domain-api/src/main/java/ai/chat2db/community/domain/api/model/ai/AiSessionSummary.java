package ai.chat2db.community.domain.api.model.ai;

import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import java.time.LocalDateTime;

public record AiSessionSummary(
        String id,
        String title,
        int sessionVersion,
        AgentRuntimeType runtimeType,
        AgentSessionStatus agentStatus,
        String modelConfigId,
        LocalDateTime gmtCreate,
        LocalDateTime gmtModified) {

    public AiSessionSummary {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (sessionVersion != 1 && sessionVersion != 2) {
            throw new IllegalArgumentException("sessionVersion must be 1 or 2");
        }
        if (sessionVersion == 1 && (runtimeType != null || agentStatus != null || modelConfigId != null)) {
            throw new IllegalArgumentException("V1 session cannot contain Agent runtime state");
        }
        if (sessionVersion == 2 && (runtimeType == null || agentStatus == null
                || modelConfigId == null || modelConfigId.isBlank())) {
            throw new IllegalArgumentException("V2 session requires Agent runtime state");
        }
    }
}
