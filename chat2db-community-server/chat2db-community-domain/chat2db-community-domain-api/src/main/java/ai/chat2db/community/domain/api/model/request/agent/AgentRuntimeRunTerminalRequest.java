package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
public class AgentRuntimeRunTerminalRequest extends AgentRuntimeLeaseRenewRequest {

    private String eventId;
    private Long sequence;
    private Date occurredAt;
}
