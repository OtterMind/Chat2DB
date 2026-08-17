package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class AgentRuntimeEventRequest extends AgentRuntimeLeaseRenewRequest {

    private Long sequence;
    private String eventId;
    private AgentRuntimeEventTypeEnum eventType;
    private String content;
    private Map<String, Object> payload = new LinkedHashMap<>();
    private Date occurredAt;
}
