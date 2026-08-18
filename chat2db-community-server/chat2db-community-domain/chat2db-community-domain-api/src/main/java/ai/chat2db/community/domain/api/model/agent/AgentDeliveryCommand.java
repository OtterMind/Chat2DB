package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentDeliveryStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentGatewayPlatformEnum;
import lombok.Data;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentDeliveryCommand {
    private String id;
    private String channelId;
    private String inboundMessageId;
    private String taskId;
    private String runId;
    private AgentGatewayPlatformEnum platform;
    private String installationRef;
    private String chatId;
    private String threadId;
    private String replyToMessageId;
    private String content;
    private List<String> attachmentRefs = new ArrayList<>();
    private String idempotencyKey;
    private AgentDeliveryStatusEnum status;
    private Integer attemptCount;
    private Date nextAttemptAt;
    private Date leaseExpiresAt;
    private String platformMessageId;
    private String lastError;
    private Date deliveredAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Long revision;
}
