package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class AgentGatewayInboundRequest {
    private String chatId;
    private String threadId;
    private String messageId;
    private String eventId;
    private String senderId;
    private String senderDisplayName;
    private String text;
    private List<String> mentions = new ArrayList<>();
    private List<ChatAttachment> attachments = new ArrayList<>();
    private Date receivedAt;
    private String idempotencyKey;
    private String agentId;
}
