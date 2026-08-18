package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentRuntimeProfileUpdateRequest extends AgentRuntimeProfileCreateRequest {

    private String profileId;
    private Long expectedRevision;
}
