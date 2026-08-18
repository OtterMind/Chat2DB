package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AgentRuntimeRunCompleteRequest extends AgentRuntimeRunTerminalRequest {

    private String finalResponse;
}
