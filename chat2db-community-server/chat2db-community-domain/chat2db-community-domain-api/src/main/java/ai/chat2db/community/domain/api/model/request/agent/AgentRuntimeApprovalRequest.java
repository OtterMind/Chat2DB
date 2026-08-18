package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentRuntimeApprovalRequest extends AgentRuntimeLeaseRenewRequest {
    private String providerRequestId;
    private String toolCallId;
    private String title;
    private Map<String, Object> requestPayload = new LinkedHashMap<>();
    private String allowOptionId;
    private String rejectOptionId;
}
