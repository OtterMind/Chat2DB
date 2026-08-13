package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import lombok.Data;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentRunEvent {

    private Long sequence;

    private String eventId;

    private String runId;

    private AgentRuntimeEventTypeEnum type;

    private String content;

    private Map<String, Object> payload = new LinkedHashMap<>();

    private Date occurredAt;

    private Date persistedAt;
}
