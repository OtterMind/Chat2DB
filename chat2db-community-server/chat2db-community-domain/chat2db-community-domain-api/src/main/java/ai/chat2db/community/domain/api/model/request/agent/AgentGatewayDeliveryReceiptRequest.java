package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentGatewayDeliveryReceiptRequest {
    private Long expectedRevision;
    private Boolean delivered;
    private String platformMessageId;
    private String error;
}
