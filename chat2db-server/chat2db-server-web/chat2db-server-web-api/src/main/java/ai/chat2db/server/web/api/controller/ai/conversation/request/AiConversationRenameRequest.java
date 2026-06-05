package ai.chat2db.server.web.api.controller.ai.conversation.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiConversationRenameRequest {

    @NotBlank
    private String title;
}
