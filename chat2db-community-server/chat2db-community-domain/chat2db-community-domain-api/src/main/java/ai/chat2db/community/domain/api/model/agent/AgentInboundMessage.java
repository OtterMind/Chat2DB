package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class AgentInboundMessage {
    private String id;
    private String channelId;
    private String bindingId;
    private String eventId;
    private String messageId;
    private String idempotencyKey;
    private String senderId;
    private String senderDisplayName;
    private String text;
    private List<String> mentions = new ArrayList<>();
    private List<ChatAttachment> attachments = new ArrayList<>();
    private String agentId;
    private String taskId;
    private Date receivedAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Long revision;
}
