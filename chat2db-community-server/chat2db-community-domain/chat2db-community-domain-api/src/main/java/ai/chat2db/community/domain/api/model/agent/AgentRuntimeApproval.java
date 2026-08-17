package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeApprovalStatusEnum;
import lombok.Data;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentRuntimeApproval {
    private String id;
    private String runId;
    private Integer leaseAttempt;
    private String providerRequestId;
    private String toolCallId;
    private String title;
    private Map<String, Object> requestPayload = new LinkedHashMap<>();
    private String allowOptionId;
    private String rejectOptionId;
    private AgentRuntimeApprovalStatusEnum status;
    private Date requestedAt;
    private Long decidedBy;
    private Date decidedAt;
    private AgentApprovalDecisionEnum decision;
    private String reason;
    private Long revision;
}
