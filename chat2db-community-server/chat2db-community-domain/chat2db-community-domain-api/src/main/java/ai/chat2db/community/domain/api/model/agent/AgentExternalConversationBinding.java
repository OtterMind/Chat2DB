package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

import java.util.Date;

@Data
public class AgentExternalConversationBinding {
    private String id;
    private String channelId;
    private String chatId;
    private String threadId;
    private String sessionId;
    private Date archivedAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Long revision;
}
