package ai.chat2db.server.web.api.controller.ai.prompt;

import java.util.List;

import ai.chat2db.server.domain.api.model.AiMessage;
import ai.chat2db.server.domain.api.service.AiConversationService;
import ai.chat2db.server.web.api.controller.ai.request.ChatQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 解析连续对话中的上一版 SQL。
 */
@Component
@Slf4j
public class PreviousSqlResolver {

    @Autowired
    private AiConversationService aiConversationService;

    public String resolve(ChatQueryRequest request) {
        if (request == null) {
            return null;
        }
        if (StringUtils.isNotBlank(request.getPreviousSql())) {
            return request.getPreviousSql();
        }
        return resolveFromDb(request.getConversationId());
    }

    private String resolveFromDb(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return null;
        }
        try {
            List<AiMessage> recent = aiConversationService.listRecentMessages(conversationId, 50);
            if (recent == null) {
                return null;
            }
            for (int i = recent.size() - 1; i >= 0; i--) {
                AiMessage msg = recent.get(i);
                if ("assistant".equals(msg.getRole()) && StringUtils.isNotBlank(msg.getSqlExtracted())) {
                    return msg.getSqlExtracted();
                }
            }
        } catch (Exception e) {
            log.warn("[PreviousSqlResolver] Failed to load previous sql for {}: {}", conversationId, e.getMessage());
        }
        return null;
    }
}
